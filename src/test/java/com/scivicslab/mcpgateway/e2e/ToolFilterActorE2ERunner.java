package com.scivicslab.mcpgateway.e2e;

/**
 * E2E runner for ToolFilterActor scenarios (006_S_access_ctrl_to_S_filter_actor).
 *
 * Usage:
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.mcpgateway.e2e.ToolFilterActorE2ERunner \
 *     [-Dexec.args="http://localhost:28001"]
 *
 * Exits non-zero if any scenario fails.
 */
public class ToolFilterActorE2ERunner {

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:28001";
        System.out.println("Gateway: " + baseUrl);

        FindToolsE2ETest test = new FindToolsE2ETest(baseUrl);
        test.scenario1_toolsListReturnsOnlyFindTools();
        test.scenario2_queryWithoutAgentNameReturnsGuidance();
        test.scenario3_queryWithAgentNameReturnsFilteredEntries();
        test.scenario4_noHealthyAgentsReturnsNoneHealthyGuidance();

        int failures = test.summarize();
        if (failures > 0) System.exit(1);
    }
}
