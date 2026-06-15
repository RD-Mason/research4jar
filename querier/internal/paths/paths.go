package paths

import (
	"fmt"
	"path/filepath"
	"runtime"
)

type DataPaths struct {
	Home     string
	Manifest string
	Shards   string
	Sessions string
}

func Resolve(explicitHome string) (DataPaths, error) {
	home, err := userHome()
	if err != nil {
		return DataPaths{}, err
	}
	return ResolveFor(explicitHome, environment(), runtime.GOOS, home)
}

func ResolveFor(explicitHome string, env map[string]string, goos, userHome string) (DataPaths, error) {
	var home string
	switch {
	case explicitHome != "":
		home = explicitHome
	case env["RESEARCH4JAR_HOME"] != "":
		home = env["RESEARCH4JAR_HOME"]
	case goos == "darwin":
		home = filepath.Join(userHome, "Library", "Application Support", "research4jar")
	case goos == "windows":
		if env["LOCALAPPDATA"] == "" {
			return DataPaths{}, fmt.Errorf(
				"LOCALAPPDATA is required on Windows when RESEARCH4JAR_HOME is unset",
			)
		}
		home = filepath.Join(env["LOCALAPPDATA"], "research4jar")
	default:
		if env["XDG_DATA_HOME"] != "" {
			home = filepath.Join(env["XDG_DATA_HOME"], "research4jar")
		} else {
			home = filepath.Join(userHome, ".local", "share", "research4jar")
		}
	}

	absolute, err := filepath.Abs(home)
	if err != nil {
		return DataPaths{}, fmt.Errorf("resolve Research4Jar home: %w", err)
	}
	return DataPaths{
		Home:     absolute,
		Manifest: filepath.Join(absolute, "manifest.db"),
		Shards:   filepath.Join(absolute, "shards"),
		Sessions: filepath.Join(absolute, "sessions"),
	}, nil
}
