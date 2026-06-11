// Package registry implements the shard distribution layout: a static file
// tree any HTTP host (object storage, GitHub Pages, an internal server) can
// serve, plus the client that downloads, verifies, and installs shards into
// the local cache. Layout, all relative to a base URL:
//
//	registry.json                    metadata: extractor version, shard count
//	v<extractor>/<jar_sha256>.db        the shard, byte-identical to a local build
//	v<extractor>/<jar_sha256>.db.sha256 hex digest of the shard file (required)
//	v<extractor>/<jar_sha256>.db.sig    base64 ed25519 signature of the shard bytes
//	                                    (required when the client has a public key)
package registry

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// ErrShardNotFound marks a registry miss (HTTP 404) as opposed to a failure.
var ErrShardNotFound = errors.New("shard not in registry")

// Client downloads shards from one registry.
type Client struct {
	BaseURL   string
	PublicKey ed25519.PublicKey // nil disables signature verification
	HTTP      *http.Client
}

// NewClient parses an optional hex-encoded ed25519 public key.
func NewClient(baseURL, publicKeyHex string) (*Client, error) {
	client := &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		HTTP:    &http.Client{Timeout: 60 * time.Second},
	}
	if publicKeyHex != "" {
		key, err := hex.DecodeString(strings.TrimSpace(publicKeyHex))
		if err != nil || len(key) != ed25519.PublicKeySize {
			return nil, errors.New(
				"registry public key must be a hex-encoded 32-byte ed25519 key",
			)
		}
		client.PublicKey = ed25519.PublicKey(key)
	}
	return client, nil
}

// FetchResult describes one installed shard.
type FetchResult struct {
	ShardID       string
	ShardPath     string
	Checksum      string
	SizeBytes     int64
	JarCoordinate *string
}

// Fetch downloads the shard for a jar hash, verifies the sha256 sidecar (and
// the ed25519 signature when a public key is configured), confirms the shard
// embeds the expected jar hash, and atomically installs it into shardsDir.
func (c *Client) Fetch(
	ctx context.Context, jarSHA256 string, extractorVersion int, shardsDir string,
) (FetchResult, error) {
	shardName := jarSHA256 + ".db"
	shardBytes, err := c.download(ctx, extractorVersion, shardName)
	if err != nil {
		return FetchResult{}, err
	}

	digest := sha256.Sum256(shardBytes)
	checksum := hex.EncodeToString(digest[:])

	sidecar, err := c.download(ctx, extractorVersion, shardName+".sha256")
	if err != nil {
		if errors.Is(err, ErrShardNotFound) {
			return FetchResult{}, fmt.Errorf(
				"registry has %s but no .sha256 sidecar; refusing unverifiable shard",
				shardName,
			)
		}
		return FetchResult{}, err
	}
	expected := strings.Fields(string(sidecar))
	if len(expected) == 0 || !strings.EqualFold(expected[0], checksum) {
		return FetchResult{}, fmt.Errorf(
			"checksum mismatch for %s: sidecar %q, downloaded %s",
			shardName, strings.Join(expected, " "), checksum,
		)
	}

	if c.PublicKey != nil {
		signature, err := c.download(ctx, extractorVersion, shardName+".sig")
		if err != nil {
			if errors.Is(err, ErrShardNotFound) {
				return FetchResult{}, fmt.Errorf(
					"public key configured but registry has no signature for %s", shardName,
				)
			}
			return FetchResult{}, err
		}
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(signature)))
		if err != nil {
			return FetchResult{}, fmt.Errorf("malformed signature for %s: %w", shardName, err)
		}
		if !ed25519.Verify(c.PublicKey, shardBytes, raw) {
			return FetchResult{}, fmt.Errorf("signature verification failed for %s", shardName)
		}
	}

	shardID := fmt.Sprintf("%s@%d", jarSHA256, extractorVersion)
	destination := filepath.Join(shardsDir, shardID+".db")
	if err := os.MkdirAll(shardsDir, 0o755); err != nil {
		return FetchResult{}, err
	}
	temp, err := os.CreateTemp(shardsDir, shardID+".download-*")
	if err != nil {
		return FetchResult{}, err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if _, err := temp.Write(shardBytes); err != nil {
		temp.Close()
		return FetchResult{}, err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return FetchResult{}, err
	}
	if err := temp.Close(); err != nil {
		return FetchResult{}, err
	}

	coordinate, err := validateShardMeta(tempPath, jarSHA256, extractorVersion)
	if err != nil {
		return FetchResult{}, fmt.Errorf("downloaded shard %s: %w", shardName, err)
	}
	if err := os.Rename(tempPath, destination); err != nil {
		return FetchResult{}, err
	}
	return FetchResult{
		ShardID:       shardID,
		ShardPath:     destination,
		Checksum:      checksum,
		SizeBytes:     int64(len(shardBytes)),
		JarCoordinate: coordinate,
	}, nil
}

func (c *Client) download(ctx context.Context, extractorVersion int, name string) ([]byte, error) {
	endpoint := fmt.Sprintf("%s/v%d/%s", c.BaseURL, extractorVersion, url.PathEscape(name))
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	response, err := c.HTTP.Do(request)
	if err != nil {
		return nil, fmt.Errorf("registry request %s: %w", endpoint, err)
	}
	defer response.Body.Close()
	switch {
	case response.StatusCode == http.StatusNotFound:
		return nil, ErrShardNotFound
	case response.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("registry returned %s for %s", response.Status, endpoint)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, fmt.Errorf("registry read %s: %w", endpoint, err)
	}
	return body, nil
}

// validateShardMeta confirms the shard's embedded identity matches what was
// requested and returns the embedded jar coordinate for the manifest row.
func validateShardMeta(path, jarSHA256 string, extractorVersion int) (*string, error) {
	slashPath := filepath.ToSlash(path)
	if filepath.VolumeName(path) != "" && !strings.HasPrefix(slashPath, "/") {
		slashPath = "/" + slashPath
	}
	uri := &url.URL{Scheme: "file", Path: slashPath}
	values := uri.Query()
	values.Set("mode", "ro")
	uri.RawQuery = values.Encode()
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	defer db.Close()
	var embeddedSHA string
	var embeddedVersion int
	var coordinate sql.NullString
	err = db.QueryRow(
		"SELECT jar_sha256, extractor_version, jar_coordinate FROM shard_meta",
	).Scan(&embeddedSHA, &embeddedVersion, &coordinate)
	if err != nil {
		return nil, fmt.Errorf("not a readable shard: %w", err)
	}
	if embeddedSHA != jarSHA256 {
		return nil, fmt.Errorf("embeds jar sha %s, expected %s", embeddedSHA, jarSHA256)
	}
	if embeddedVersion != extractorVersion {
		return nil, fmt.Errorf(
			"embeds extractor version %d, expected %d", embeddedVersion, extractorVersion,
		)
	}
	if coordinate.Valid && coordinate.String != "" {
		return &coordinate.String, nil
	}
	return nil, nil
}
