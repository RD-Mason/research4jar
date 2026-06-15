package registry

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"dev.research4jar/querier/internal/manifest"
)

// Metadata is written to registry.json at the export root so tooling can
// discover what a registry serves without listing it.
type Metadata struct {
	ExtractorVersion int    `json:"extractor_version"`
	ShardCount       int    `json:"shard_count"`
	Signed           bool   `json:"signed"`
	GeneratedBy      string `json:"generated_by"`
}

// ExportResult summarizes an export run.
type ExportResult struct {
	OutputDir  string `json:"output_dir"`
	Exported   int    `json:"exported"`
	Skipped    int    `json:"skipped"`
	TotalBytes int64  `json:"total_bytes"`
	Signed     bool   `json:"signed"`
}

// Export copies every current-version shard from the local cache into the
// static registry layout under outputDir, writing sha256 sidecars and, when a
// signing key is provided, ed25519 signatures. Shards whose files are missing
// or whose checksum no longer matches the manifest are skipped with a warning
// on warnings.
func Export(
	shards []manifest.Shard,
	extractorVersion int,
	outputDir string,
	signingKey ed25519.PrivateKey,
	generatedBy string,
	warnings io.Writer,
) (ExportResult, error) {
	versionDir := filepath.Join(outputDir, fmt.Sprintf("v%d", extractorVersion))
	if err := os.MkdirAll(versionDir, 0o755); err != nil {
		return ExportResult{}, err
	}
	result := ExportResult{OutputDir: outputDir, Signed: signingKey != nil}
	for _, shard := range shards {
		if shard.ExtractorVersion != extractorVersion {
			result.Skipped++
			continue
		}
		shardBytes, err := os.ReadFile(shard.ShardPath)
		if err != nil {
			fmt.Fprintf(warnings, "warning: %s: %v; skipping\n", shard.ShardID, err)
			result.Skipped++
			continue
		}
		digest := sha256.Sum256(shardBytes)
		checksum := hex.EncodeToString(digest[:])
		if shard.ShardChecksum != nil && *shard.ShardChecksum != checksum {
			fmt.Fprintf(
				warnings,
				"warning: %s: on-disk shard does not match manifest checksum; skipping\n",
				shard.ShardID,
			)
			result.Skipped++
			continue
		}
		name := shard.JarSHA256 + ".db"
		if err := os.WriteFile(filepath.Join(versionDir, name), shardBytes, 0o644); err != nil {
			return ExportResult{}, err
		}
		sidecar := fmt.Sprintf("%s  %s\n", checksum, name)
		err = os.WriteFile(filepath.Join(versionDir, name+".sha256"), []byte(sidecar), 0o644)
		if err != nil {
			return ExportResult{}, err
		}
		if signingKey != nil {
			signature := base64.StdEncoding.EncodeToString(
				ed25519.Sign(signingKey, shardBytes),
			)
			err = os.WriteFile(
				filepath.Join(versionDir, name+".sig"), []byte(signature+"\n"), 0o644,
			)
			if err != nil {
				return ExportResult{}, err
			}
		}
		result.Exported++
		result.TotalBytes += int64(len(shardBytes))
	}

	metadata, err := json.MarshalIndent(Metadata{
		ExtractorVersion: extractorVersion,
		ShardCount:       result.Exported,
		Signed:           result.Signed,
		GeneratedBy:      generatedBy,
	}, "", "  ")
	if err != nil {
		return ExportResult{}, err
	}
	err = os.WriteFile(filepath.Join(outputDir, "registry.json"), append(metadata, '\n'), 0o644)
	if err != nil {
		return ExportResult{}, err
	}
	return result, nil
}

// GenerateKey creates an ed25519 keypair, writes the hex-encoded private key
// to keyPath (0600), and returns the hex-encoded public key clients configure
// for verification.
func GenerateKey(keyPath string) (publicKeyHex string, err error) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(keyPath), 0o755); err != nil {
		return "", err
	}
	content := hex.EncodeToString(private) + "\n"
	if err := os.WriteFile(keyPath, []byte(content), 0o600); err != nil {
		return "", err
	}
	return hex.EncodeToString(public), nil
}

// LoadSigningKey reads a hex-encoded ed25519 private key written by
// GenerateKey.
func LoadSigningKey(keyPath string) (ed25519.PrivateKey, error) {
	content, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, err
	}
	raw, err := hex.DecodeString(strings.TrimSpace(string(content)))
	if err != nil || len(raw) != ed25519.PrivateKeySize {
		return nil, errors.New("signing key must be a hex-encoded 64-byte ed25519 private key")
	}
	return ed25519.PrivateKey(raw), nil
}
