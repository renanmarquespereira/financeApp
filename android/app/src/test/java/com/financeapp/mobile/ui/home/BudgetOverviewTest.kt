package com.financeapp.mobile.ui.home

import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.BudgetDto
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class BudgetOverviewTest {
    @Test fun annualIncludesOtherMonthsOfSameYearAndMonthlyOnlySelectedMonth() {
        val transactions = listOf(tx(1,1,-10.0,"2026-01-15"),tx(2,1,-30.0,"2026-09-06"),
            tx(3,1,-50.0,"2025-09-06"),tx(4,1,-90.0,"2027-01-01"),tx(5,1,100.0,"2026-02-01"))
        val budgets = listOf(BudgetDto(1,1,100.0))
        val month = monthlyBudgetOverview(budgets,emptyList(),transactions,YearMonth.of(2026,9))
        val year = monthlyBudgetOverview(budgets,emptyList(),transactions,YearMonth.of(2026,9),annual=true)
        assertEquals(30.0,month.rows.single().spent,0.001)
        assertEquals(40.0,year.rows.single().spent,0.001)
        assertEquals(40.0,year.totalSpent,0.001)
        assertEquals(1200.0,year.rows.single().limit,0.001)
        assertEquals(1160.0,year.rows.single().remaining,0.001)
        assertEquals(70.0,month.rows.single().remaining,0.001)
    }
    private fun tx(id: Int, category: Int?, amount: Double, date: String = "2026-09-06") = TransactionEntity(
        id=id, accountId=null, categoryId=category, date=date, description="Teste", amount=amount,
        transactionType="manual", status="posted", source="manual", externalTransactionId=null, userId=1, workspaceId="company")
    @Test fun separatesLimitsExpensesAndUnbudgetedSpending() {
        val result=monthlyBudgetOverview(listOf(BudgetDto(1,1,100.0),BudgetDto(2,2,50.0),BudgetDto(3,3,20.0)), emptyList(),
            listOf(tx(1,1,-120.0),tx(2,2,-10.0),tx(3,null,-8.0),tx(4,9,-2.0),tx(5,1,500.0),
                tx(6,1,-900.0,"2026-08-31"),tx(7,1,-600.0,"invalid")),YearMonth.of(2026,9))
        assertEquals(170.0,result.planned,0.001)
        assertEquals(140.0,result.totalSpent,0.001)
        assertEquals(130.0,result.budgetedSpent,0.001)
        assertEquals(10.0,result.unbudgetedSpent,0.001)
        assertEquals(-20.0,result.rows.first { it.id==1 }.remaining,0.001)
        assertEquals(40.0,result.rows.first { it.id==2 }.remaining,0.001)
        assertEquals(20.0,result.rows.first { it.id==3 }.remaining,0.001)
    }
    @Test fun noBudgetsDoesNotHideTotalSpending() {
        val result=monthlyBudgetOverview(emptyList(),emptyList(),listOf(tx(1,null,-50.0)),YearMonth.of(2026,9))
        assertEquals(50.0,result.totalSpent,0.001)
        assertEquals(50.0,result.unbudgetedSpent,0.001)
        assertEquals(0.0,result.planned,0.001)
    }
    @Test fun zeroLimitAndDuplicateBudgetDoNotDoubleCount() {
        val result=monthlyBudgetOverview(listOf(BudgetDto(1,1,0.0),BudgetDto(1,1,0.0)),emptyList(),
            listOf(tx(1,1,-5.0)),YearMonth.of(2026,9))
        assertEquals(1,result.rows.size)
        assertEquals(-5.0,result.remaining,0.001)
    }
}
