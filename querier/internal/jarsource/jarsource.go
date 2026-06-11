// Package jarsource resolves a --jars specification (directory, glob, or
// comma-separated list) into jar paths, mirroring the Kotlin JarSource
// semantics so registry prefetch sees the same jar set the indexer will.
package jarsource

import (
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Resolve expands a specification into absolute, deduplicated, sorted jar
// paths. Entries may be directories (walked recursively for *.jar), globs
// (** crosses directories, * and ? stay within one segment), or literal
// file paths.
func Resolve(specification string) ([]string, error) {
	if strings.TrimSpace(specification) == "" {
		return nil, nil
	}
	seen := map[string]bool{}
	var result []string
	for _, entry := range strings.Split(specification, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		paths, err := resolveOne(entry)
		if err != nil {
			return nil, err
		}
		for _, path := range paths {
			absolute, err := filepath.Abs(path)
			if err != nil {
				return nil, err
			}
			absolute = filepath.Clean(absolute)
			if !seen[absolute] {
				seen[absolute] = true
				result = append(result, absolute)
			}
		}
	}
	sort.Strings(result)
	return result, nil
}

func resolveOne(value string) ([]string, error) {
	if strings.ContainsAny(value, "*?[{") {
		return resolveGlob(value)
	}
	info, err := os.Stat(value)
	if err == nil && info.IsDir() {
		var jars []string
		walkErr := filepath.WalkDir(value, func(path string, entry fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if !entry.IsDir() && isJar(entry.Name()) {
				jars = append(jars, path)
			}
			return nil
		})
		return jars, walkErr
	}
	return []string{value}, nil
}

func resolveGlob(value string) ([]string, error) {
	firstGlob := strings.IndexAny(value, "*?[{")
	separator := strings.LastIndexAny(value[:firstGlob], `/\`)
	rootText := "."
	if separator >= 0 {
		rootText = value[:separator+1]
	}
	pattern := strings.ReplaceAll(value[separator+1:], `\`, "/")
	root, err := filepath.Abs(rootText)
	if err != nil {
		return nil, err
	}
	if _, err := os.Stat(root); err != nil {
		return nil, nil
	}
	matcher, err := globToRegexp(pattern)
	if err != nil {
		return nil, err
	}
	var jars []string
	walkErr := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		if matcher.MatchString(filepath.ToSlash(relative)) && isJar(entry.Name()) {
			jars = append(jars, path)
		}
		return nil
	})
	return jars, walkErr
}

// globToRegexp compiles a glob pattern: ** crosses path separators, * and ?
// match within one segment, [...] character classes pass through, and {a,b}
// becomes alternation.
func globToRegexp(pattern string) (*regexp.Regexp, error) {
	var builder strings.Builder
	builder.WriteString("^")
	runes := []rune(pattern)
	for index := 0; index < len(runes); index++ {
		switch character := runes[index]; character {
		case '*':
			if index+1 < len(runes) && runes[index+1] == '*' {
				index++
				// `**/` also matches zero directories.
				if index+1 < len(runes) && runes[index+1] == '/' {
					index++
					builder.WriteString(`(?:[^/]+/)*`)
				} else {
					builder.WriteString(`.*`)
				}
			} else {
				builder.WriteString(`[^/]*`)
			}
		case '?':
			builder.WriteString(`[^/]`)
		case '[':
			end := strings.IndexRune(pattern[index:], ']')
			if end < 0 {
				builder.WriteString(`\[`)
			} else {
				builder.WriteString(pattern[index : index+end+1])
				index += end
			}
		case '{':
			builder.WriteString(`(?:`)
		case '}':
			builder.WriteString(`)`)
		case ',':
			builder.WriteString(`|`)
		default:
			builder.WriteString(regexp.QuoteMeta(string(character)))
		}
	}
	builder.WriteString("$")
	return regexp.Compile(builder.String())
}

func isJar(name string) bool {
	return strings.HasSuffix(strings.ToLower(name), ".jar")
}
