package query

import (
	"os"
	"path/filepath"
)

func mkdirParent(path string) error {
	return os.MkdirAll(filepath.Dir(path), 0o755)
}
