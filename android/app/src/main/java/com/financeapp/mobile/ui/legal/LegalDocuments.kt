package com.financeapp.mobile.ui.legal

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant

object FinanceAppLegal {
    const val TERMS_VERSION = "1.0-2026-09-20"
    const val PRIVACY_VERSION = "1.0-2026-09-20"
    const val TERMS = """TERMOS DE USO DO FINANCEAPP
Versão 1.0 — 20/09/2026

1. OBJETO
O FinanceApp é uma ferramenta de organização, registro, acompanhamento, planejamento e análise de informações financeiras pessoais. Pode oferecer contas, cartões, faturas, categorias, metas, orçamentos, previsões, relatórios, sincronização, backup, importação e inteligência artificial.

2. NATUREZA INFORMATIVA
O FinanceApp não é instituição financeira, banco, corretora, contabilidade, consultoria de investimentos ou aconselhamento financeiro profissional. Previsões, alertas, classificações e respostas de IA são informativos. O usuário deve conferir informações relevantes e permanece responsável por suas decisões financeiras.

3. OPEN FINANCE E INTEGRAÇÕES
Quando habilitado, Open Finance será usado para importação, consulta, conciliação e organização de informações autorizadas. Nesta versão o FinanceApp não inicia pagamentos ou transferências bancárias. Integrações de terceiros também podem estar sujeitas aos termos desses fornecedores.

4. CADASTRO E SEGURANÇA
O usuário deve fornecer dados corretos, proteger credenciais e impedir acesso indevido ao aparelho. Biometria, PIN e bloqueio são proteções adicionais.

5. LOCAL-FIRST, SINCRONIZAÇÃO E BACKUP
Em recursos compatíveis, dados são salvos primeiro no aparelho e sincronizados posteriormente. Falta de internet ou indisponibilidade do servidor pode atrasar a sincronização sem significar perda do registro local. O usuário deve acompanhar pendências e backups. A restauração de backup pode substituir dados locais do Workspace selecionado e exige conferência antes da confirmação.

6. INTELIGÊNCIA ARTIFICIAL
A IA pode utilizar o contexto necessário para gerar resumos, explicações, previsões e sugestões. Respostas podem conter imprecisões e não substituem orientação profissional.

7. DISPONIBILIDADE E USO ADEQUADO
O serviço pode passar por manutenção ou indisponibilidade. É proibido utilizá-lo para fraude, acesso indevido, exploração de vulnerabilidades ou atividade ilícita.

8. RESPONSABILIDADE
Dados importados podem depender de instituições e fornecedores externos. Saldos, faturas, vencimentos e lançamentos relevantes devem ser conferidos com a fonte oficial quando necessário. Estes Termos não afastam direitos que não possam ser excluídos pela legislação brasileira.

9. EXCLUSÃO E RETENÇÃO
O usuário pode utilizar os mecanismos disponibilizados para excluir conta e dados. Informações poderão ser conservadas quando necessário para obrigação legal, exercício regular de direitos, prevenção a fraude ou outra base legal aplicável.

10. ALTERAÇÕES
Versões relevantes destes Termos poderão exigir nova concordância. A versão aceita e a data/hora do aceite podem ser registradas para auditoria.

11. LEGISLAÇÃO
Aplicam-se as leis brasileiras, inclusive normas de proteção do consumidor e de dados quando cabíveis, preservado o foro legalmente assegurado ao consumidor.

12. IDENTIFICAÇÃO E CONTATO
Antes da distribuição pública/comercial, a versão jurídica final deve informar a identificação do responsável/controlador, CNPJ/CPF quando aplicável, endereço/canal de contato e contato de privacidade, e deve ser revisada por profissional jurídico."""

    const val PRIVACY = """POLÍTICA DE PRIVACIDADE DO FINANCEAPP
Versão 1.0 — 20/09/2026

1. ESCOPO
Esta Política descreve como o FinanceApp trata dados pessoais no cadastro e no uso. Antes do lançamento comercial, deve ser complementada com a identificação e contato do controlador.

2. DADOS TRATADOS
Conforme os recursos usados, podem ser tratados nome, e-mail, CPF, nascimento, sexo informado, foto, identificadores técnicos e de sessão; contas, bancos, cartões, faturas, transações, categorias, orçamentos, metas, recorrências, Workspaces e demais informações financeiras inseridas ou importadas; além de informações de sincronização, diagnóstico, segurança e contexto necessário à IA.

3. FINALIDADES
Criar e proteger a conta; autenticar; fornecer funcionalidades financeiras; sincronizar, restaurar e fazer backup; importar e conciliar dados autorizados; gerar relatórios, previsões e análises; fornecer IA; prevenir fraude e erros; prestar suporte; e cumprir obrigações legais.

4. BASES LEGAIS
O tratamento deve observar a LGPD e a base adequada a cada finalidade, como execução de contrato/procedimentos preliminares, obrigação legal, legítimo interesse quando cabível e consentimento quando efetivamente necessário. Aceitar estes documentos não constitui consentimento genérico para todo tratamento.

5. ARMAZENAMENTO E SINCRONIZAÇÃO
O FinanceApp possui arquitetura local-first em recursos compatíveis. Dados podem permanecer no aparelho e ser sincronizados com infraestrutura de servidor para continuidade, backup e acesso autenticado.

6. FORNECEDORES E COMPARTILHAMENTO
Dados podem ser processados por provedores necessários à operação, como hospedagem, banco de dados, autenticação, e-mail, Open Finance e IA, conforme os recursos realmente habilitados. A política pública deverá refletir e identificar adequadamente os fornecedores/categorias usados em produção.

7. OPEN FINANCE
Dados de Open Finance são tratados dentro do escopo autorizado. Nesta versão a integração é de consulta/importação e não inicia pagamentos.

8. INTELIGÊNCIA ARTIFICIAL
Quando uma funcionalidade de IA exigir processamento externo, deve ser enviado somente o contexto necessário. O aplicativo deve informar adequadamente o uso e evitar dados pessoais desnecessários.

9. SEGURANÇA
São adotadas medidas técnicas e organizacionais compatíveis com o produto, incluindo autenticação, proteção de sessão, controles locais e backup/sincronização. Nenhum sistema oferece risco zero.

10. RETENÇÃO E EXCLUSÃO
Dados são mantidos pelo período necessário às finalidades e obrigações aplicáveis. O usuário pode solicitar exclusão pelos mecanismos do aplicativo, ressalvadas retenções legalmente necessárias e ciclos técnicos de backup.

11. DIREITOS DO TITULAR
Nos termos da LGPD, podem ser exercidos direitos de confirmação, acesso, correção, anonimização/bloqueio/eliminação quando cabíveis, portabilidade conforme regulamentação, informações sobre compartilhamento, revogação de consentimento e demais direitos previstos em lei, após validação segura da identidade.

12. CRIANÇAS E ADOLESCENTES
A política comercial, classificação etária e fluxo de cadastro devem observar os requisitos legais aplicáveis antes da distribuição pública.

13. TRANSFERÊNCIA INTERNACIONAL
Se fornecedores processarem dados fora do Brasil, a transferência deve observar a LGPD e regulamentação da ANPD.

14. INCIDENTES
Incidentes envolvendo dados pessoais serão avaliados e tratados conforme a legislação, incluindo comunicações obrigatórias quando aplicáveis.

15. ATUALIZAÇÕES E CONTATO
Mudanças relevantes poderão ser apresentadas ao usuário e exigir novo aceite quando cabível. Antes do lançamento público, esta Política deve conter identificação do controlador, contato de privacidade e os fornecedores efetivamente utilizados, além de passar por revisão jurídica."""

    const val DATA_SUMMARY = """SOBRE SEUS DADOS

• Dados cadastrais e financeiros são usados para entregar as funcionalidades escolhidas.
• Em recursos compatíveis, o FinanceApp é local-first: salvar no aparelho e sincronizar são etapas diferentes.
• A Central de Diagnóstico mostra pendências e integridade.
• Backup independente é proteção adicional e não substitui sincronização.
• Open Finance é usado para consulta/importação; esta versão não inicia pagamentos.
• IA é informativa e pode usar o contexto necessário para gerar respostas.
• Exclusão da conta e dados está disponível na Zona de segurança, ressalvadas retenções legalmente necessárias.
• Antes da publicação comercial, os documentos devem receber identificação do controlador, contatos e revisão jurídica.

Versões: Termos ${TERMS_VERSION} | Privacidade ${PRIVACY_VERSION}"""
}

object LegalAcceptanceStore {
    private const val PREFS = "financeapp_legal"
    fun record(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("terms_version", FinanceAppLegal.TERMS_VERSION)
        .putString("privacy_version", FinanceAppLegal.PRIVACY_VERSION)
        .putString("accepted_at", Instant.now().toString()).apply()
    fun acceptedCurrent(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("terms_version", null) == FinanceAppLegal.TERMS_VERSION && p.getString("privacy_version", null) == FinanceAppLegal.PRIVACY_VERSION
    }
}

private enum class LegalPage { HUB, TERMS, PRIVACY, DATA }

@Composable
fun LegalPrivacyDialog(onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(LegalPage.HUB) }
    val title = when (page) { LegalPage.HUB -> "Legal e privacidade"; LegalPage.TERMS -> "Termos de Uso"; LegalPage.PRIVACY -> "Política de Privacidade"; LegalPage.DATA -> "Sobre seus dados" }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Gavel, null) }, title = { Text(title) }, text = {
        if (page == LegalPage.HUB) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Documentos e informações sobre o tratamento de dados no FinanceApp.")
            Button(onClick = { page = LegalPage.TERMS }, modifier = Modifier.fillMaxWidth()) { Text("Termos de Uso") }
            OutlinedButton(onClick = { page = LegalPage.PRIVACY }, modifier = Modifier.fillMaxWidth()) { Text("Política de Privacidade") }
            OutlinedButton(onClick = { page = LegalPage.DATA }, modifier = Modifier.fillMaxWidth()) { Text("Sobre seus dados") }
            Text("Termos: ${FinanceAppLegal.TERMS_VERSION}\nPrivacidade: ${FinanceAppLegal.PRIVACY_VERSION}", style = MaterialTheme.typography.bodySmall)
        } else {
            val content = when (page) { LegalPage.TERMS -> FinanceAppLegal.TERMS; LegalPage.PRIVACY -> FinanceAppLegal.PRIVACY; else -> FinanceAppLegal.DATA_SUMMARY }
            Text(content, modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { if (page == LegalPage.HUB) TextButton(onClick = onDismiss) { Text("Fechar") } else TextButton(onClick = { page = LegalPage.HUB }) { Text("Voltar") } })
}

@Composable
fun LegalConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    var showDocuments by remember { mutableStateOf(false) }
    if (showDocuments) LegalPrivacyDialog { showDocuments = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Antes de continuar") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Para usar o FinanceApp, confirme que leu e concorda com os Termos de Uso e que leu a Política de Privacidade.")
        TextButton(onClick = { showDocuments = true }) { Text("Ler Termos e Política de Privacidade") }
        Text("O aceite não representa consentimento genérico para tratamentos que exijam base legal específica.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { Button(onClick = onAccept) { Text("Aceitar e continuar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
