package com.scivicslab.mcpgateway.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * E2E tests for find_tools tool and ToolFilterActor dispatch.
 * Requires a running gateway. Scenarios correspond to
 * 006_S_access_ctrl_to_S_filter_actor spec.
 */
public class FindToolsE2ETest {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private int passed = 0;
    private int failed = 0;

    public FindToolsE2ETest(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // --- scenario 1 ---

    public void scenario1_toolsListReturnsOnlyFindTools() throws Exception {
        String body = rpc("1", "tools/list", "{}");
        String resp = postAll(body);
        JsonNode tools = mapper.readTree(resp).path("result").path("tools");

        if (!tools.isArray()) {
            fail("scenario1", "tools/list did not return an array: " + resp);
            return;
        }
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));

        if (names.size() == 1 && names.get(0).equals("find_tools")) {
            pass("scenario1", "tools/list returned only find_tools");
        } else {
            fail("scenario1", "expected [find_tools] but got " + names);
        }
    }

    // --- scenario 2 ---

    public void scenario2_queryWithoutAgentNameReturnsGuidance() throws Exception {
        String agentName = findFirstHealthyAgent();
        if (agentName == null) {
            skip("scenario2", "no healthy chat-ui agent registered — skipped");
            return;
        }

        String text = callFindTools("2", "send to another agent");

        if (text.startsWith("[")) {
            fail("scenario2", "expected guidance text, got JSON array: " + text);
        } else if (text.contains(agentName)) {
            pass("scenario2", "guidance mentions agent '" + agentName + "'");
        } else {
            fail("scenario2", "guidance does not mention '" + agentName + "': " + text);
        }
    }

    // --- scenario 3 ---

    public void scenario3_queryWithAgentNameReturnsFilteredEntries() throws Exception {
        List<String> agents = findAllHealthyAgents();
        if (agents.size() < 2) {
            skip("scenario3", "need at least 2 healthy agents — found " + agents.size() + ", skipped");
            return;
        }

        String target = agents.get(0);
        String other = agents.get(1);
        String text = callFindTools("3", "send to " + target);

        if (text.startsWith("[")) {
            JsonNode arr = mapper.readTree(text);
            boolean hasTarget = false;
            boolean hasOther = false;
            for (JsonNode e : arr) {
                String sn = e.path("serverName").asText();
                if (sn.equals(target)) hasTarget = true;
                if (sn.equals(other)) hasOther = true;
            }
            if (hasTarget && !hasOther) {
                pass("scenario3", "returned only entries for '" + target + "'");
            } else {
                fail("scenario3", "hasTarget=" + hasTarget + " hasOther=" + hasOther + " in: " + text);
            }
        } else {
            fail("scenario3", "expected JSON array, got guidance: " + text);
        }
    }

    // --- scenario 4 ---

    public void scenario4_noHealthyAgentsReturnsNoneHealthyGuidance() throws Exception {
        if (!findAllHealthyAgents().isEmpty()) {
            skip("scenario4", "healthy agents exist — 'none currently healthy' path not reachable, skipped");
            return;
        }

        String text = callFindTools("4", "send to another agent");

        if (!text.startsWith("[") && text.contains("none currently healthy")) {
            pass("scenario4", "guidance says 'none currently healthy'");
        } else {
            fail("scenario4", "unexpected response: " + text);
        }
    }

    // --- summary ---

    public int summarize() {
        System.out.printf("%nResult: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    // --- helpers ---

    private String callFindTools(String id, String query) throws Exception {
        String escapedQuery = query.replace("\"", "\\\"");
        String params = "{\"name\":\"find_tools\",\"arguments\":{\"query\":\"" + escapedQuery + "\"}}";
        String body = rpc(id, "tools/call", params);
        String resp = postAll(body);
        return mapper.readTree(resp)
                .path("result").path("content").get(0).path("text").asText();
    }

    private String postAll(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/_all"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String rpc(String id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private String findFirstHealthyAgent() throws Exception {
        List<String> agents = findAllHealthyAgents();
        return agents.isEmpty() ? null : agents.get(0);
    }

    private List<String> findAllHealthyAgents() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/servers"))
                .GET()
                .build();
        String resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        JsonNode arr = mapper.readTree(resp);
        List<String> result = new ArrayList<>();
        for (JsonNode s : arr) {
            String name = s.path("name").asText();
            boolean healthy = s.path("healthy").asBoolean();
            if (healthy && name.startsWith("chat-ui-")) {
                result.add(name);
            }
        }
        return result;
    }

    private void pass(String scenario, String msg) {
        System.out.println("PASS [" + scenario + "] " + msg);
        passed++;
    }

    private void fail(String scenario, String msg) {
        System.out.println("FAIL [" + scenario + "] " + msg);
        failed++;
    }

    private void skip(String scenario, String msg) {
        System.out.println("SKIP [" + scenario + "] " + msg);
    }
}
