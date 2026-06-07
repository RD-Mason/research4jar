package main

import "testing"

func TestParseOptionsAllowsFlagsBeforeAndAfterPrefix(t *testing.T) {
	options, err := parseOptions([]string{
		"--page", "2",
		"spring.datasource",
		"--format", "text",
		"--page-size", "5",
	})
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
	if _, err := parseOptions([]string{"spring.datasource", "--page", "0"}); err == nil {
		t.Fatal("expected an error")
	}
}
