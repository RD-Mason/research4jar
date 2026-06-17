package main

import (
	"strings"
	"testing"
)

func TestParseOptionsAllowsFlagsBeforeAndAfterPrefix(t *testing.T) {
	options, err := parseOptions([]string{
		"--page", "2",
		"spring.datasource",
		"--format", "text",
		"--page-size", "5",
	}, false)
	if err != nil {
		t.Fatal(err)
	}
	if options.arg != "spring.datasource" ||
		options.page != 2 ||
		options.pageSize != 5 ||
		options.format != "text" {
		t.Fatalf("unexpected options: %#v", options)
	}
}

func TestParseOptionsRejectsInvalidPagination(t *testing.T) {
	if _, err := parseOptions([]string{"spring.datasource", "--page", "0"}, false); err == nil {
		t.Fatal("expected an error")
	}
}

func TestParseOptionsDirectFlagAndOptionalArg(t *testing.T) {
	options, err := parseOptions([]string{"--direct", "jakarta.servlet.Filter"}, false)
	if err != nil {
		t.Fatal(err)
	}
	if !options.direct || options.arg != "jakarta.servlet.Filter" {
		t.Fatalf("unexpected options: %#v", options)
	}

	if _, err := parseOptions([]string{}, false); err == nil {
		t.Fatal("expected an error for missing argument")
	}
	options, err = parseOptions([]string{}, true)
	if err != nil {
		t.Fatal(err)
	}
	if options.arg != "" {
		t.Fatalf("unexpected options: %#v", options)
	}
}

func TestParseOptionsSourceGrepFlag(t *testing.T) {
	options, err := parseOptions([]string{"--no-source-grep", "example.Service"}, false)
	if err != nil {
		t.Fatal(err)
	}
	if options.sourceGrep || options.arg != "example.Service" {
		t.Fatalf("unexpected options: %#v", options)
	}
}

func TestNoProjectIndexMessageGivesRecoveryPath(t *testing.T) {
	message := noProjectIndexMessage("/tmp/example project")
	for _, expected := range []string{
		"research4jar index --project-dir",
		"research4jar status",
		"research4jar doctor",
		"/tmp/example project",
	} {
		if !strings.Contains(message, expected) {
			t.Fatalf("message missing %q: %s", expected, message)
		}
	}
}

func TestUnknownCommandMessageSuggestsLikelyCommands(t *testing.T) {
	message := unknownCommandMessage("stats")
	for _, expected := range []string{
		"unknown command: stats",
		"Did you mean:",
		"research4jar status",
		"research4jar --help",
	} {
		if !strings.Contains(message, expected) {
			t.Fatalf("message missing %q: %s", expected, message)
		}
	}

	suggestions := commandSuggestions("find-config-property")
	if len(suggestions) == 0 || suggestions[0] != "find-config-properties" {
		t.Fatalf("unexpected suggestions: %#v", suggestions)
	}
}

func TestCommandSuggestionsAvoidLowConfidenceGuesses(t *testing.T) {
	if suggestions := commandSuggestions("zzzzzz"); len(suggestions) != 0 {
		t.Fatalf("unexpected suggestions: %#v", suggestions)
	}
}
