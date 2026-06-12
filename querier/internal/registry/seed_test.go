package registry

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseCoordinates(t *testing.T) {
	input := `
# Spring Boot core
org.springframework.boot:spring-boot:3.5.0

org.springframework:spring-core:6.2.7
`
	coordinates, err := ParseCoordinates(strings.NewReader(input))
	if err != nil {
		t.Fatal(err)
	}
	if len(coordinates) != 2 {
		t.Fatalf("coordinates = %v, want 2", coordinates)
	}
	if coordinates[0].String() != "org.springframework.boot:spring-boot:3.5.0" {
		t.Fatalf("first = %s", coordinates[0])
	}

	if _, err := ParseCoordinates(strings.NewReader("not-a-coordinate\n")); err == nil {
		t.Fatal("malformed line should fail parsing")
	}
}

func TestCoordinateJarURL(t *testing.T) {
	coordinate := Coordinate{
		Group: "org.springframework.boot", Artifact: "spring-boot", Version: "3.5.0",
	}
	got := coordinate.JarURL("https://repo.example.com/maven2/")
	want := "https://repo.example.com/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0.jar"
	if got != want {
		t.Fatalf("url = %s, want %s", got, want)
	}
}

func TestDownloadJarsSkipsFailuresAndKeepsRest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			if strings.Contains(request.URL.Path, "missing-artifact") {
				http.NotFound(writer, request)
				return
			}
			writer.Write([]byte("jar bytes for " + request.URL.Path))
		},
	))
	defer server.Close()

	coordinates := []Coordinate{
		{Group: "com.example", Artifact: "present", Version: "1.0"},
		{Group: "com.example", Artifact: "missing-artifact", Version: "1.0"},
	}
	destDir := t.TempDir()
	var warnings strings.Builder
	jars, failures := DownloadJars(
		context.Background(), server.URL, coordinates, destDir, &warnings,
	)
	if len(jars) != 1 || failures != 1 {
		t.Fatalf("jars = %v, failures = %d; want 1 and 1", jars, failures)
	}
	if filepath.Base(jars[0]) != "present-1.0.jar" {
		t.Fatalf("downloaded = %s", jars[0])
	}
	content, err := os.ReadFile(jars[0])
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), "present/1.0/present-1.0.jar") {
		t.Fatalf("unexpected content: %s", content)
	}
	if !strings.Contains(warnings.String(), "missing-artifact") {
		t.Fatalf("warning missing: %q", warnings.String())
	}
}
