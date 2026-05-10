.PHONY: build test lint clean release tidy

BIN_DIR := bin
BINARY  := thindb
CMD     := ./cmd/thindb

build:
	go build -o $(BIN_DIR)/$(BINARY) $(CMD)

test:
	go test -v -race -cover ./...

lint:
	golangci-lint run ./...

tidy:
	go mod tidy

clean:
	rm -rf $(BIN_DIR)

release:
	GOOS=linux   GOARCH=amd64  go build -o $(BIN_DIR)/$(BINARY)-linux-amd64   $(CMD)
	GOOS=darwin  GOARCH=amd64  go build -o $(BIN_DIR)/$(BINARY)-darwin-amd64  $(CMD)
	GOOS=darwin  GOARCH=arm64  go build -o $(BIN_DIR)/$(BINARY)-darwin-arm64  $(CMD)
	GOOS=windows GOARCH=amd64  go build -o $(BIN_DIR)/$(BINARY)-windows-amd64.exe $(CMD)
	@echo "Release binaries written to $(BIN_DIR)/"
	@ls -lh $(BIN_DIR)/
