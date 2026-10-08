package com.financeapp.mobile.data.deletion
import org.junit.Test
import org.junit.Assert.*
private fun row(id: Int, ws: String = "w1", vararg extra: Pair<String, String?>): LocalRow = LocalRow(
    linkedMapOf("id" to id.toString(), "userId" to "7", "workspaceId" to ws, *extra))
private fun fixture(): DeletionSnapshot {
    val ws=listOf(LocalRow(linkedMapOf("userId" to "7","id" to "w1","name" to "Principal","kind" to "personal","isDefault" to "1","archivedAt" to null)),
        LocalRow(linkedMapOf("userId" to "7","id" to "w2","name" to "Empresa","kind" to "business","isDefault" to "0","archivedAt" to null)))
    val tables=DeletionPlanner.tableNames.associateWith { emptyList<LocalRow>() }.toMutableMap()
    tables["accounts"] = listOf(row(1,"w1","institutionName" to "Bank 1", "syncState" to "PENDING_CREATE"),row(1,"w2","institutionName" to "Bank 2", "syncState" to "SYNCED"))
    tables["credit_cards_local"] = listOf(row(1,"w1","bankName" to "Card 1"),row(1,"w2","bankName" to "Card 2"))
    tables["categories"] = listOf(row(1,"w1","name" to "Food"),row(2,"w1","name" to "Transport"),row(1,"w2","name" to "Other"))
    tables["transactions"] = listOf(
        row(1,"w1","accountId" to "1","categoryId" to "1","cardId" to null,"date" to "2026-09-27T12:00:00","source" to "manual","syncState" to "PENDING_CREATE","amount" to "10"),
        row(2,"w1","accountId" to null,"categoryId" to "1","cardId" to "1","date" to "2026-10-01T12:00:00","source" to "card_purchase","syncState" to "PENDING_CREATE","amount" to "20"),
        row(3,"w1","accountId" to null,"categoryId" to "2","cardId" to "1","date" to "2026-10-05T12:00:00","source" to "card_payment","syncState" to "SYNCED","amount" to "30"),
        row(4,"w1","accountId" to null,"categoryId" to "1","cardId" to null,"date" to "2026-11-01T12:00:00","source" to "manual","syncState" to "SYNCED","amount" to "40"),
        row(1,"w2","accountId" to "1","categoryId" to "1","cardId" to "1","date" to "2026-10-01T12:00:00","source" to "card_purchase","syncState" to "PENDING_CREATE","amount" to "50"))
    tables["budgets_local"] = listOf(row(100,"w1","categoryId" to "1","amount" to "500"),row(200,"w2","categoryId" to "1","amount" to "700"))
    tables["goals_local"] = listOf(row(1,"w1","name" to "Trip"),row(1,"w2","name" to "Office"))
    tables["goal_contributions_local"] = listOf(row(1,"w1","goalId" to "1","amount" to "20"),row(1,"w2","goalId" to "1","amount" to "30"))
    tables["pending_sync_operations"] = listOf(
        row(1,"w1","entityType" to "TRANSACTION","entityId" to "1","action" to "CREATE"),
        row(2,"w1","entityType" to "ATTACHMENT","entityId" to "2","action" to "CREATE","payload" to """{"storedName":"receipt.png"}"""),
        row(3,"w1","entityType" to "CATEGORY","entityId" to "1","action" to "CREATE"),
        row(4,"w1","entityType" to "GOAL_CONTRIBUTION","entityId" to "1","action" to "CREATE"),
        row(5,"w2","entityType" to "TRANSACTION","entityId" to "1","action" to "CREATE"))
    val prefs=listOf(
        LocalPreference("forecast_sync_v3","7:w1","string","""{"scenarios":[{"id":101}],"deletedScenarioIds":[],"aiPlans":[{"id":201}],"deletedAiPlanIds":[],"debts":[{"id":301,"payments":[{"accountId":1}]}],"deletedDebtIds":[]}"""),
        LocalPreference("forecast_scenarios","forecast_scenarios_local_w1","string","""[{"id":101},{"id":102}]"""),
        LocalPreference("forecast_scenarios","forecast_deleted_scenarios_local_w1","set","[99]"),
        LocalPreference("ai_plans","plans_local_workspace_w1","string","""[{"id":201}]"""),
        LocalPreference("financeapp_debts","financeapp_debts_v1_local_w1","string","""[{"id":301,"accountId":1}]"""),
        LocalPreference("financeapp_category_rules_local_Principal","food","int","1"),
        LocalPreference("forecast_scenarios","forecast_scenarios_local_w2","string","""[{"id":999}]"""))
    return DeletionSnapshot(7,"w1",ws,tables,prefs,setOf("local"))
}
class DeletionPlannerTest {
    private fun plan(c: DeletionChoice) = DeletionPlanner.build(fixture(), c)
    @Test fun previewDoesNotMutateTheSnapshot() {
        val s=fixture(); val before=DeletionPlanner.snapshotHash(s)
        DeletionPlanner.build(s,DeletionChoice(allFinancial=true))
        assertEquals(before, DeletionPlanner.snapshotHash(s))
    }
    @Test fun bankDeletionIncludesItsTransactionsOnly() {
        val p=plan(DeletionChoice(accountIds=setOf(1)))
        assertEquals(1,p.counts.getValue("transactions"))
        assertEquals(setOf("w1"),p.scopeIds)
    }
    @Test fun cardDeletionIncludesPurchasesAndPaymentRecords() {
        assertEquals(2,plan(DeletionChoice(cardIds=setOf(1))).counts.getValue("transactions"))
    }
    @Test fun categoriesUnlinkWithoutDeletingTransactions() {
        val p=plan(DeletionChoice(categoryIds=setOf(1)))
        assertEquals(0,p.counts.getValue("transactions"))
        assertTrue(p.statements.any{it.sql.startsWith("UPDATE transactions SET categoryId = NULL")})
        assertTrue(p.preferenceWrites.any{it.kind=="remove" && it.file.startsWith("financeapp_category_rules_")})
    }
    @Test fun purchaseFilterDoesNotDeleteCardPayment() {
        assertEquals(1,plan(DeletionChoice(transactions=true,transactionMode="cards")).counts.getValue("transactions"))
    }
    @Test fun dateFilterIsInclusive() {
        assertEquals(2,plan(DeletionChoice(transactions=true,fromDate="2026-10-01",toDate="2026-10-31")).counts.getValue("transactions"))
    }
    @Test fun individualTransactionsAreScoped() {
        assertEquals(1,plan(DeletionChoice(transactions=true,transactionMode="selected",transactionIds=setOf(1))).counts.getValue("transactions"))
    }
    @Test fun scenariosHaveTombstonesAndDoNotErasePlans() {
        val p=plan(DeletionChoice(forecasts=true))
        assertEquals(2,p.counts.getValue("scenarios"));assertEquals(0,p.counts.getValue("plans"))
        assertTrue(p.preferenceWrites.any{it.key=="forecast_deleted_scenarios_local_w1" && it.value.contains("101")})
    }
    @Test fun planningIncludesGoalsContributionsBudgetsAndSavedPlans() {
        val p=plan(DeletionChoice(planning=true))
        listOf("goals","contributions","budgets","plans").forEach{assertEquals(1,p.counts.getValue(it))}
    }
    @Test fun allDataDefaultsToCurrentWorkspaceIncludingPendingRecords() {
        assertEquals(4,plan(DeletionChoice(allFinancial=true)).counts.getValue("transactions"))
    }
    @Test fun allWorkspacesRequiresExplicitSelection() {
        assertEquals(5,plan(DeletionChoice(allFinancial=true,allWorkspaces=true)).counts.getValue("transactions"))
    }
    @Test fun deletingCurrentWorkspaceSwitchesToSurvivor() {
        assertEquals("w2",plan(DeletionChoice(workspaceIds=setOf("w1"),deleteWorkspaces=true)).nextWorkspaceId)
    }
    @Test fun deletingLastWorkspaceCreatesAnEmptyPrincipal() {
        val p=plan(DeletionChoice(workspaceIds=setOf("w1","w2"),deleteWorkspaces=true))
        assertTrue(p.nextWorkspaceId!!.startsWith("local-"))
        assertTrue(p.statements.any{it.sql.startsWith("INSERT INTO workspaces_local")})
    }
    @Test fun previewRejectsOtherOwners() {
        val s=fixture(); val contaminated=s.copy(userId=99)
        assertTrue(runCatching{DeletionPlanner.build(contaminated,DeletionChoice(allFinancial=true))}.isFailure)
    }
    @Test fun unknownWorkspaceCannotBeSelected() {
        assertTrue(runCatching{plan(DeletionChoice(workspaceIds=setOf("unknown"),deleteWorkspaces=true))}.isFailure)
    }
    @Test fun invertedDateRangeIsRejected() {
        assertTrue(runCatching{plan(DeletionChoice(transactions=true,fromDate="2026-11-01",toDate="2026-10-01"))}.isFailure)
    }
    @Test fun identicalWorkspaceNamesDoNotShareLegacyWrites() {
        val s=fixture();val duplicate=s.copy(workspaces=s.workspaces.map{it.copy(values=it.values+mapOf("name" to "Same"))})
        val p=DeletionPlanner.build(duplicate,DeletionChoice(forecasts=true))
        assertTrue(p.preferenceWrites.none{it.key.endsWith("_Same")})
    }
    @Test fun previewContainsOnlyScopedParameterizedSql() {
        val p=plan(DeletionChoice(allFinancial=true))
        assertTrue(p.statements.all{it.sql.contains("userId = ? AND workspaceId = ?") && it.args[0]=="7" && it.args[1]=="w1"})
    }
}
