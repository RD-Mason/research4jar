package paths

import (
	"os"
	"strings"
)

var userHome = os.UserHomeDir

func environment() map[string]string {
	result := make(map[string]string)
	for _, item := range os.Environ() {
		key, value, found := strings.Cut(item, "=")
		if found {
			result[key] = value
		}
	}
	return result
}
