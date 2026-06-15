package depgraph

import (
	"strings"
	"testing"
)

func TestParseTGFBuildsDependencyPaths(t *testing.T) {
	graph, err := ParseTGF(strings.NewReader(`
1 com.example:app:jar:1.0
2 com.example:direct:jar:1.1:compile
3 com.example:transitive:jar:2.0:runtime
4 com.example:classified:jar:sources:3.0:runtime
#
1 2
2 3
2 4
`))
	if err != nil {
		t.Fatal(err)
	}
	if graph.BuildTool != "maven" || len(graph.Artifacts) != 4 {
		t.Fatalf("unexpected graph: %#v", graph)
	}
	byCoordinate := map[string]Artifact{}
	for _, artifact := range graph.Artifacts {
		byCoordinate[artifact.Coordinate] = artifact
	}
	direct := byCoordinate["com.example:direct:1.1"]
	if !direct.Direct || direct.Depth != 1 || direct.Parent != "com.example:app:1.0" {
		t.Fatalf("unexpected direct artifact: %#v", direct)
	}
	transitive := byCoordinate["com.example:transitive:2.0"]
	if transitive.Direct || transitive.Depth != 2 ||
		strings.Join(transitive.Path, " -> ") !=
			"com.example:direct:1.1 -> com.example:transitive:2.0" {
		t.Fatalf("unexpected transitive artifact: %#v", transitive)
	}
	classified := byCoordinate["com.example:classified:3.0"]
	if classified.Classifier != "sources" || classified.Scope != "runtime" {
		t.Fatalf("classifier/scope not parsed: %#v", classified)
	}
}
