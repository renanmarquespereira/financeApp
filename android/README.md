# Finance App Android V3

Versão Android corrigida para AGP 9 conectada ao backend Finance App V10.

## O que já funciona

- Cadastro por nome, e-mail e senha
- Login
- Armazenamento do JWT no dispositivo
- Retrofit para consumir o FastAPI
- Room para cache local de contas e transações
- Dashboard
- Lista de contas
- Lista de transações
- Identificação visual de `manual` vs `open_finance`
- Criação de transação manual
- Conexão com o Mock Open Finance
- Sincronização manual
- Simulação de nova transação automática
- Atualização local automática a cada 30 segundos
- Logout

## Requisitos

- Android Studio Quail 3 / 2026.1.3 ou mais recente
- JDK 17
- Android SDK 37
- Backend V10 rodando no computador
- Docker com `api`, `postgres`, `redis`, `worker` e `beat` ativos

## 1. Inicie o backend

Na pasta do backend V10:

```powershell
docker compose up
```

Confirme:

```powershell
docker compose ps
```

E abra no navegador:

http://localhost:8000/docs

## 2. Abra este projeto no Android Studio

No Android Studio:

File > Open

Selecione a pasta `finance-app-android-v1`.

Espere o Gradle Sync terminar.

## 3. Testar no emulador

O projeto já está configurado com:

```kotlin
http://10.0.2.2:8000/
```

`10.0.2.2` é o endereço que o emulador Android usa para acessar o `localhost` do computador.

Execute um emulador e clique em Run.

## 4. Testar em celular físico

No celular físico, `10.0.2.2` NÃO funciona.

Descubra o IP do computador no Windows:

```powershell
ipconfig
```

Procure o IPv4 da rede, por exemplo:

```text
192.168.1.50
```

Altere em `app/build.gradle.kts`:

```kotlin
buildConfigField(
    "String",
    "API_BASE_URL",
    "\"http://192.168.1.50:8000/\""
)
```

O computador e o celular precisam estar na mesma rede Wi‑Fi. O Firewall do Windows também precisa permitir a porta 8000.

Depois faça Sync Project with Gradle Files e execute novamente.

## Fluxo de teste recomendado

1. Crie usuário no app ou use um usuário existente.
2. Faça login.
3. Toque em **Conectar banco**.
4. O app chama `POST /openfinance/mock/connect`.
5. Toque em **Sincronizar**.
6. Contas e movimentações deverão aparecer.
7. Toque em **Simular nova transação automática**.
8. A movimentação será criada pelo Mock e importada pelo worker.
9. Use `+` para cadastrar um lançamento manual.
10. Compare:
   - nuvem = `open_finance`
   - lápis = `manual`

## Sobre a Belvo

O código Android V1 usa o Mock Open Finance para desenvolvimento.

Quando o provider real estiver disponível, a interface Android poderá manter praticamente o mesmo fluxo. A camada de integração fica no backend.

## Próximas etapas sugeridas

- Login Google / Credential Manager
- Refresh automático do JWT
- tela de categorias
- edição e exclusão de lançamentos manuais
- filtros por período/banco/categoria
- gráficos
- WorkManager para sincronização em background do cache local
- push notifications
- biometria
- HTTPS em produção
- política de privacidade e termos


## Correção da V2 — AGP 9

A V1 aplicava `org.jetbrains.kotlin.android`, mas AGP 9 já possui Kotlin integrado.
A V2 remove esse plugin e migra os processadores de anotação de `kapt` para KSP.

Alterações:
- removido `org.jetbrains.kotlin.android`;
- removido `org.jetbrains.kotlin.kapt`;
- adicionado KSP 2.3.10;
- Hilt compiler migrado de `kapt(...)` para `ksp(...)`;
- Room compiler migrado de `kapt(...)` para `ksp(...)`;
- removido o bloco legado `kotlinOptions`.

Ao abrir a V2, use **Sync Project with Gradle Files**.


## V3
Filtros combináveis por origem, tipo (entradas/saídas), banco, mês e período; resumo filtrado de entradas e saídas; data em cada transação.


## V3.1

Novidades:
- valores de entrada em verde;
- valores de saída em vermelho;
- totais de entradas/saídas coloridos;
- e-mail do usuário logado exibido na aba **Contas**;
- toque e segure uma transação para excluir;
- confirmação antes da exclusão;
- transações Open Finance excluídas não voltam após sincronização quando o backend V10.1 é usado.

### Importante

Para a exclusão funcionar corretamente, use o backend **Finance App V10.1** junto com esta versão Android.


## V3.2

Alterações de interface:
- removidos os ícones de atualizar/logout do lado do título "Finance App";
- a engrenagem aparece somente quando a aba **Contas** está aberta;
- ao tocar na engrenagem, abre uma janela mostrando o e-mail conectado;
- a mesma janela possui a opção **Sair da conta**;
- o e-mail não fica mais exposto diretamente na tela de contas;
- transações podem ser excluídas de duas formas:
  - toque e segure;
  - arraste para a esquerda ou para a direita;
- os dois gestos abrem uma confirmação antes da exclusão;
- entradas continuam verdes e saídas vermelhas.

Use junto com o backend V10.1 para que transações Open Finance excluídas
não reapareçam na próxima sincronização.


## V3.4

Alterações:
- removida a aba Resumo;
- saldo, entradas, saídas e total de transações agora aparecem no topo da aba Transações;
- cada transação mostra banco e tipo da movimentação;
- correção visual do fundo vermelho do gesto de exclusão:
  o vermelho só aparece durante o swipe e fica recortado no mesmo formato do card.
