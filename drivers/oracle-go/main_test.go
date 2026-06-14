package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestHandshakeResponse(t *testing.T) {
	s := newServer()
	resp, shutdown := s.handleLine(`{"jsonrpc":"2.0","id":7,"method":"handshake","params":{"appVersion":"dev"}}`)
	if shutdown {
		t.Fatal("handshake should not shut down the server")
	}
	if resp.Error != nil {
		t.Fatalf("unexpected error: %v", resp.Error)
	}
	data, err := json.Marshal(resp.Result)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		ProtocolVersion      int      `json:"protocolVersion"`
		AgentProtocolVersion int      `json:"agentProtocolVersion"`
		Capabilities         []string `json:"capabilities"`
	}
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	if result.ProtocolVersion != 1 || result.AgentProtocolVersion != 1 {
		t.Fatalf("unexpected protocol versions: %+v", result)
	}
	if !contains(result.Capabilities, "query") || !contains(result.Capabilities, "metadata") {
		t.Fatalf("expected query and metadata capabilities, got %v", result.Capabilities)
	}
}

func TestCloseMissingQuerySessionReturnsFalse(t *testing.T) {
	s := newServer()
	resp, shutdown := s.handleLine(`{"jsonrpc":"2.0","id":8,"method":"close_query_session","params":{"sessionId":"missing"}}`)
	if shutdown {
		t.Fatal("close_query_session should not shut down the server")
	}
	if resp.Error != nil {
		t.Fatalf("unexpected error: %v", resp.Error)
	}
	if resp.Result != false {
		t.Fatalf("expected false result, got %#v", resp.Result)
	}
}

func TestEmptyResultSlicesMarshalAsArrays(t *testing.T) {
	data, err := json.Marshal(queryResult{})
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if strings.Contains(text, `"columns":null`) || strings.Contains(text, `"rows":null`) {
		t.Fatalf("query result should marshal nil slices as arrays: %s", text)
	}

	data, err = json.Marshal(indexInfo{})
	if err != nil {
		t.Fatal(err)
	}
	text = string(data)
	if strings.Contains(text, `"columns":null`) || strings.Contains(text, `"included_columns":null`) {
		t.Fatalf("index info should marshal nil slices as arrays: %s", text)
	}
}

func TestBuildDSNUsesConnectionStringWhenProvided(t *testing.T) {
	dsn := buildDSN(connectParams{ConnectionString: "oracle://scott:tiger@db.example.com:1521/ORCLPDB1"})

	if dsn != "oracle://scott:tiger@db.example.com:1521/ORCLPDB1" {
		t.Fatalf("unexpected dsn: %s", dsn)
	}
}

func TestBuildDSNUsesJdbcServiceHostAndPort(t *testing.T) {
	dsn := buildDSN(connectParams{
		Host:             "127.0.0.1",
		Port:             11521,
		Database:         "ORCLPDB1",
		Username:         "scott",
		Password:         "tiger",
		ConnectionString: "jdbc:oracle:thin:@//oracle.example.com:1521/ORCLPDB1",
	})

	if strings.Contains(strings.ToLower(dsn), "jdbc:") {
		t.Fatalf("dsn should be go-ora format, got: %s", dsn)
	}
	if !strings.Contains(dsn, "oracle.example.com:1521") || !strings.Contains(dsn, "ORCLPDB1") {
		t.Fatalf("dsn should use JDBC host/port/database fields, got: %s", dsn)
	}
}

func TestBuildDSNUsesRewrittenJdbcServiceHostAndPort(t *testing.T) {
	dsn := buildDSN(connectParams{
		Host:             "127.0.0.1",
		Port:             11521,
		Database:         "ORCLPDB1",
		Username:         "scott",
		Password:         "tiger",
		ConnectionString: "jdbc:oracle:thin:@//127.0.0.1:11521/ORCLPDB1",
	})

	if strings.Contains(strings.ToLower(dsn), "jdbc:") {
		t.Fatalf("dsn should be go-ora format, got: %s", dsn)
	}
	if !strings.Contains(dsn, "127.0.0.1:11521") || !strings.Contains(dsn, "ORCLPDB1") {
		t.Fatalf("dsn should use rewritten JDBC host/port/database fields, got: %s", dsn)
	}
}

func TestBuildDSNConvertsJdbcSID(t *testing.T) {
	dsn := buildDSN(connectParams{
		Host:             "127.0.0.1",
		Port:             11521,
		Database:         "ORCL",
		Username:         "scott",
		Password:         "tiger",
		ConnectionString: "jdbc:oracle:thin:@oracle.example.com:1521:ORCL",
	})

	if strings.Contains(strings.ToLower(dsn), "jdbc:") {
		t.Fatalf("dsn should be go-ora format, got: %s", dsn)
	}
	upperDSN := strings.ToUpper(dsn)
	if !strings.Contains(dsn, "oracle.example.com:1521") || !strings.Contains(upperDSN, "SID=ORCL") {
		t.Fatalf("dsn should use JDBC host/port and SID option, got: %s", dsn)
	}
}

func TestBuildDSNConvertsJdbcDescriptor(t *testing.T) {
	dsn := buildDSN(connectParams{
		Username:         "scott",
		Password:         "tiger",
		ConnectionString: "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db.example.com)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=ORCLPDB1)))",
	})

	if !strings.HasPrefix(dsn, "oracle://scott:tiger@") {
		t.Fatalf("descriptor should become go-ora url, got: %s", dsn)
	}
	if !strings.Contains(dsn, "connStr=") {
		t.Fatalf("descriptor should be passed via connStr option, got: %s", dsn)
	}
}

func TestBuildDSNAddsSysDbaOption(t *testing.T) {
	dsn := buildDSN(connectParams{
		Host:      "127.0.0.1",
		Port:      1521,
		Database:  "SYSDBA:ORCLPDB1",
		Username:  "sys",
		Password:  "secret",
		SysDBA:    true,
		URLParams: "TRACE FILE=trace.log",
	})

	if strings.Contains(dsn, "SYSDBA:") {
		t.Fatalf("dsn should strip SYSDBA prefix: %s", dsn)
	}
	if !strings.Contains(dsn, "ORCLPDB1") {
		t.Fatalf("dsn should include service name: %s", dsn)
	}
	upperDSN := strings.ToUpper(dsn)
	if !strings.Contains(upperDSN, "AUTH TYPE=SYSDBA") &&
		!strings.Contains(upperDSN, "AUTH+TYPE=SYSDBA") &&
		!strings.Contains(upperDSN, "AUTH%20TYPE=SYSDBA") {
		t.Fatalf("dsn should include SYSDBA auth option: %s", dsn)
	}
}

func contains(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
