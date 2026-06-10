package main

import "testing"

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
