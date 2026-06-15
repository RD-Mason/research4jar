package paths

import (
	"path/filepath"
	"testing"
)

func TestResolveFor(t *testing.T) {
	tests := []struct {
		name     string
		explicit string
		env      map[string]string
		goos     string
		userHome string
		want     string
	}{
		{
			name:     "explicit",
			explicit: "/custom",
			env:      map[string]string{"RESEARCH4JAR_HOME": "/environment"},
			goos:     "linux",
			userHome: "/home/test",
			want:     "/custom",
		},
		{
			name:     "environment",
			env:      map[string]string{"RESEARCH4JAR_HOME": "/environment"},
			goos:     "darwin",
			userHome: "/Users/test",
			want:     "/environment",
		},
		{
			name:     "macOS",
			env:      map[string]string{},
			goos:     "darwin",
			userHome: "/Users/test",
			want:     "/Users/test/Library/Application Support/research4jar",
		},
		{
			name:     "Linux XDG",
			env:      map[string]string{"XDG_DATA_HOME": "/xdg"},
			goos:     "linux",
			userHome: "/home/test",
			want:     "/xdg/research4jar",
		},
		{
			name:     "Linux fallback",
			env:      map[string]string{},
			goos:     "linux",
			userHome: "/home/test",
			want:     "/home/test/.local/share/research4jar",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := ResolveFor(test.explicit, test.env, test.goos, test.userHome)
			if err != nil {
				t.Fatal(err)
			}
			want, err := filepath.Abs(filepath.FromSlash(test.want))
			if err != nil {
				t.Fatal(err)
			}
			if got.Home != want {
				t.Fatalf("Home = %q, want %q", got.Home, want)
			}
			if got.Manifest != filepath.Join(got.Home, "manifest.db") {
				t.Fatalf("Manifest = %q", got.Manifest)
			}
		})
	}
}

func TestResolveForWindowsRequiresLocalAppData(t *testing.T) {
	_, err := ResolveFor("", map[string]string{}, "windows", `C:\Users\test`)
	if err == nil {
		t.Fatal("expected an error")
	}
}
