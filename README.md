# Archeo Lucky 🎰✅

App **100% Kotlin** (Android) que abre o Google, detecta quando o jogo **Archéo** (PrideBet MZ) é aberto e mostra:


- 🔔 Toast **"jogo Lindo com sucesso ✅✅"** ao detectar o jogo;
- 💰 **Saldo do jogo em tempo real** no canto superior da tela (HUD fixo);
- ⚙️ **Janela flutuante** (botão G) com **stake**, **nº de cliques** e **operação automática**.

O APK é compilado **automaticamente no GitHub Actions** a cada push — sem Android Studio, sem PC.

---

## v1.5 — Martingale (Mi Mines e Archéo)

### Mi Mines
Após uma **perda** entra com **3 minas e 5 cliques** (padrão fixado) e o stake vira
`base × multiplicador^passo`.

Exemplo, base **48** e ×2: `48 → 96 → 192`. Seu exemplo "perdeu 40 → 48" é o mesmo
raciocínio: os 40 são o valor *perdido*, e o gale de 48 já cobre a perda com margem
(payout de 5 cliques/3 minas ≈ 1,96x).

### Archéo
Após uma perda entra com **3 cliques** (padrão fixado) e o stake também é dobrado
(`saldo perdido × 2`).

### Controles do painel (aparecem nos dois jogos)
| Controle | Faixa | Padrão |
|---|---|---|
| **MARTINGALE** (ativado/desativado) | on/off | ATIVADO |
| **MULTIPLICADOR DO GALE** | ×1,10 a ×5,00 | ×2,00 |
| **LIMITE DE GALES** | 1 a 10 | 3 |
| **∞ SEM LIMITE** (ignora o limite) | sim/não | NÃO |

Ao **ganhar**, o stake volta ao valor base e o passo zera. Ao **estourar o limite**,
a operação **para sozinha** e o stake volta ao base.

O martingale roda dentro do motor JS, não no app: quem sabe o resultado da rodada é o
frame do jogo, então o stake da próxima aposta já sai calculado, sem corrida de tempo.

## v1.4 — Aviator com aposta automática

O painel **G** agora se adapta ao jogo aberto.

### Aviator (`pridebet.co.mz/games/Aviator-10`)

| Controle | O que faz |
|---|---|
| **STAKE** | valor da aposta (passo de 10 MZN). Padrão ao abrir: **100,00** |
| **ALVO / AUTO-CASHOUT** | saída automática, padrão **2,00x** (passo de 0,10) |
| **REGRA DE ENTRADA** | `APÓS VELA BAIXA` (aposta só na vela seguinte a um resultado abaixo do alvo) ou `SEMPRE` |
| **NOMES DOS JOGADORES** | `ANÔNIMOS (d***4)` ou `APELIDOS (Jogador 1…10)` |

Como funciona: o motor lê a fita de payouts (`.payouts-block .payout`) dentro do frame
da Spribe, aplica a regra sobre a **última vela fechada** e, se for o caso, clica em
**Bet** na janela de apostas seguinte. O cash-out é feito pelo próprio jogo (switch
Auto ligado + alvo gravado). O saldo sobe para o HUD em tempo real.

Limites conhecidos:
- se o clique em Bet não entrar em até 2,5 s, a rodada é **pulada** de propósito (evita clique duplo que cancelaria a aposta);
- mudar stake/alvo no meio do voo reaplica a configuração e pode perder a janela daquela rodada;
- o motor **não** prevê nada: a regra é mecânica. Em 169 velas medidas, as rodadas se mostraram independentes (χ² = 0,89 contra 3,84 crítico).

### Nomes dos jogadores
O servidor da Spribe entrega o nome **já mascarado** (`d***0` … `d***9` — no demo são
10 bots). O nome real não existe no aparelho, então não há como "desanonimizar".
O que o app faz é trocar **o texto na tela** por um rótulo estável (`Jogador 1`…`Jogador 10`)
via MutationObserver — é apelido local, cosmético, não é o nome verdadeiro.

## 📲 Baixar o APK

1. Vá na aba **Actions** deste repositório;
2. Abra o último run verde **Build Android APK**;
3. Em **Artifacts**, baixe **`archeo-lucky-debug-apk`** (é um .zip com o `app-debug.apk` dentro);
4. No celular, extraia e instale o `app-debug.apk` (permita "instalar de fontes desconhecidas").

> Também dá para baixar direto em **Releases** quando uma tag `v*` é enviada.

## 🕹️ Como usar

1. Abre o app → carrega o **Google**;
2. Pesquise/entre em `pridebet.co.mz` → aba **Minas** → **Archéo** → **PRATICAR** (ou JOGAR);
3. Assim que o jogo carregar: **toast** aparece e o HUD no topo começa a mostrar o **saldo real** do jogo;
4. O botão amarelo **G** (canto inferior direito) volta para o Google.


## ⚙️ Janela flutuante de configuração (v1.1)

Toque no botão flutuante **G** → abre uma **janela flutuante** (arrastável pela barra de título,
com botão **—** para minimizar e **✕** para fechar):

| Controle | O que faz |
|---|---|
| **Stake / Aposta (FUN)** | `−` / `+` de 0,50 em 0,50 (1,00 a 100,00). Aplica no campo real do jogo. |
| **Cliques por sessão** | de 1 a 10 casas por rodada. |
| **▶ OPERAÇÃO AUTOMÁTICA** | liga/desliga o bot: ele clica **COMEÇAR**, escolhe as casas **aleatoriamente**, avança os níveis e faz **CASHOUT** no fim — repetindo até você parar. |
| **Status** | sessões feitas, W/L e o stake/cliques em uso, em tempo real. |

A janela mostra `W · L` no cabeçalho mesmo minimizada. O botão **Ir para o Google** fica dentro do painel.

### Como o app manda comandos para dentro do iframe do jogo

`evaluateJavascript` só alcança o **frame principal** — e o jogo vive num iframe de outra origem.
A ponte é feita em duas direções:

```text
app → jogo :  evaluateJavascript no frame principal
              → push(window) + push(iframe.contentWindow)  [postMessage '*']
              → script injetado em cada frame faz RELAY até o iframe do provedor
              → dedupe por id (o comando pode chegar 2x, executa 1x)

jogo → app :  window.ArcheoBridge.postMessage(json)
              → WebViewCompat.addWebMessageListener recebe de qualquer frame
```

### O motor automático (mesma lógica do laboratório)

Portei a máquina de estados que usei no Playwright para JavaScript puro, rodando **dentro do
frame do jogo**:

1. `dismissOverlays()` — fecha o modal "Bem-vindo!"/backdrops se estiverem abertos;
2. garante o stake (`input.stake-input` via setter nativo + eventos `input/change`);
3. clica **COMEÇAR** e espera a linha ativa ficar clicável;
4. para cada clique: sorteia 1 das 4 casas da linha ativa e dispara
   `pointerdown → mousedown → pointerup → mouseup → click`;
5. detecta o resultado: linha ativa avançou = **sobreviveu**; tabuleiro revelou = **caveira**;
6. se sobreviveu a todos os cliques → **CASHOUT**; conta W/L e recomeça.

> 🧪 Testado contra o jogo real: **34 sessões em 45 s** (16 W / 18 L), saldo atualizando na tela
> e comando de parada atendido imediatamente.

---

## 💰 Como o saldo é lido (a parte difícil)

O Archéo não roda na página da PrideBet: ele vive num **iframe de outro domínio**
(`splitthepot.games/pridebet-mz/stages/archeo`). Um `evaluateJavascript` normal só enxerga o
frame principal — e a *same-origin policy* impede ler o `contentDocument` do iframe.

Solução com as APIs do **androidx.webkit**:

| API | Para quê |
|---|---|
| `WebViewCompat.addDocumentStartJavaScript(webview, js, setOf("*"))` | injeta um observador em **todos os frames**, inclusive o iframe cross-origin |
| `WebViewCompat.addWebMessageListener(webview, "ArcheoBridge", setOf("*"), listener)` | recebe as mensagens vindas de **qualquer frame** |

O observador injetado faz poll (~500ms) no texto do frame, casa com a regex
`Saldo\s*([0-9][0-9\.\s]*,[0-9]{2})\s*FUN` e envia o valor via `ArcheoBridge.postMessage`.
O app recebe, atualiza o HUD e pulsa o pontinho verde "AO VIVO".

Se o WebView do aparelho for antigo (sem `DOCUMENT_START_SCRIPT`), há fallback por
`evaluateJavascript` no frame principal (funciona quando o jogo é aberto como página
principal, ex.: link direto do provedor).

### Detecção do jogo

`isArcheo(url)` = URL contém `archeo` **e** (`pridebet` ou `splitthepot` ou `playdemo` ou `/games/archeo`).
O toast dispara uma vez por sessão (reset ao sair do jogo).

---

## 🧱 Stack

- Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7 · JDK 17
- `minSdk 24` · `targetSdk 34` · `compileSdk 34`
- `androidx.webkit:webkit:1.11.0` · `androidx.core:core-ktx:1.13.1`
- Sem Java, sem Compose, sem bibliotecas de terceiros.

## 🛠️ Build local (opcional)

```bash
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```

## ⚠️ Aviso

App de estudo/entretenimento. Todos os saldos exibidos vêm do **modo demonstração** da
plataforma (fichas FUN, sem dinheiro real). Não é afiliado à PrideBet nem ao provedor do jogo.
Jogos de azar têm margem estrutural a favor da casa. Proibido para menores de 18 anos.


## v1.3 — Stake no Mi Mines + investigação do RNG

- O **stake agora é aplicado no Mi Mines**. O jogo só aceita valores de uma escada fixa de 15 níveis
  (**8,00 · 16,00 · 24,00 · 32,00 · 40,00 · 48,00 · 56,00 · 64,00 · 96,00 · 160,00 · 320,00 · 800,00 · 1.600,00 · 4.000,00 · 8.000,00 MZN**),
  então o app **encosta** o valor escolhido no nível mais próximo e mostra o valor real no painel.
- Os botões **− / +** do painel passam a andar **direto na escada** quando o Mi Mines está aberto
  (no Archéo continuam em passos de 0,50 FUN).
- O rótulo do painel troca de moeda sozinho: **MZN** no Mi Mines, **FUN** no Archéo.

## v1.2 — Mi Mines

- Abrir o **Mi Mines** (`pridebet.co.mz/games/Mines-6`) exibe o toast **"Mi Mines Carregado ✅✅✅"**.
- Nova opção em **Configurações**: **MINAS (MI MINES)** de **1 a 20** (stepper − / +), aplicada no jogo antes de cada rodada.
- Motor automático para o Mi Mines: tabuleiro é `<canvas>`, então o clique é feito por **evento sintético na coordenada** da casa sorteada.
- Saldo em tempo real passa a mostrar **MZN** (Mi Mines) ou **FUN** (Archéo), detectado automaticamente.

