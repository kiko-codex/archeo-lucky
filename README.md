# Archeo Lucky 🎰✅

App **100% Kotlin** (Android) que abre o Google, detecta quando o jogo **Archéo** (PrideBet MZ) é aberto e mostra:

- 🔔 Toast **"jogo Lindo com sucesso ✅✅"** ao detectar o jogo;
- 💰 **Saldo do jogo em tempo real** no canto superior da tela (HUD fixo).

O APK é compilado **automaticamente no GitHub Actions** a cada push — sem Android Studio, sem PC.

---

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

---

## ⚙️ Como o saldo é lido (a parte difícil)

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
