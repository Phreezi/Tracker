# Tracker — localização familiar

App Android + servidor web para acompanhar, de forma **transparente**, onde está um
telemóvel (por exemplo, o de um filho/filha que começou a andar de autocarro sozinho/a).
O telemóvel envia a localização para o teu servidor e tu vês tudo num site privado, no
computador ou no iPhone.

> ⚠️ **Uso responsável.** A app mostra **sempre** uma notificação permanente no telemóvel
> enquanto está a partilhar a localização — não é, nem deve ser, escondida. Monitorizar
> alguém deve ser do conhecimento dessa pessoa. Em Portugal/UE, vigiar secretamente outra
> pessoa pode ser ilegal. Para menores a teu cargo, fala com ela e explica.

## O que faz

- **App Android (APK)** instalada no telemóvel a acompanhar:
  - lê a localização de **1 em 1 minuto** (configurável no painel);
  - **não envia nada se a pessoa se moveu menos de 15 m** no último minuto (filtro feito
    pelo próprio Android → pouca bateria);
  - usa prioridade **equilibrada** (rede/Wi-Fi quando chega, GPS só quando é preciso);
  - **sinal de vida** periódico (ex.: de 10 em 10 min) mesmo parada, com a % de bateria;
  - **fila offline**: se ficar sem rede, guarda e envia tudo quando voltar;
  - reinicia sozinha depois de o telemóvel arrancar;
  - **modo ao vivo**: a partir do site podes pedir envios de 15 em 15 s (ou menos) durante
    uns minutos quando quiseres acompanhar em tempo quase real.

- **Servidor + painel web** (no teu computador/alojamento):
  - mapa com a **posição atual** e **histórico por dia** (percurso desenhado);
  - deteta se está **nas aulas** (defines a escola e o horário no painel);
  - mostra a bateria e há quanto tempo foi o último sinal;
  - tudo protegido por **palavra-passe** (painel) e **token** (telemóvel).

- **Aba "Pessoal"** no painel (por baixo das *Definições*): partilha a **tua própria**
  localização diretamente do navegador, sem instalares a app no teu telemóvel. Como o painel
  já está protegido por palavra-passe, basta manter a página aberta — a tua posição aparece no
  mapa com um marcador verde, guardada à parte da do telemóvel monitorizado.

Sem base de dados externa nem dependências: só Node.js (usa o SQLite embutido).

---

## 1) Pôr o servidor a correr

Precisas de **Node.js 22.5 ou superior**.

```bash
cd server
cp .env.example .env            # edita o .env e escolhe ADMIN_PASSWORD e DEVICE_TOKEN
# gera um token aleatório forte:
#   openssl rand -hex 24
set -a; . ./.env; set +a        # carrega as variáveis
npm start
```

Abre `http://localhost:8080` e entra com a `ADMIN_PASSWORD`.

**Para o telemóvel chegar ao servidor pela internet** precisas de um endereço público.
Opções:
- alojar numa VPS/serviço com HTTPS (recomendado);
- em casa, usar um túnel como `cloudflared` ou `ngrok` (dá-te um `https://...` temporário);
- Docker: `docker build -t tracker server && docker run -p 8080:8080 --env-file server/.env -v $PWD/data:/app/data tracker`.

Usa **HTTPS** sempre que possível (o token viaja em cada pedido).

### Variáveis de ambiente

| Variável | Para quê | Exemplo |
|---|---|---|
| `ADMIN_PASSWORD` | Palavra-passe do painel | `uma-coisa-dificil` |
| `DEVICE_TOKEN` | Segredo partilhado com a app (≥16 car.) | `openssl rand -hex 24` |
| `PORT` | Porta | `8080` |
| `TZ_SCHOOL` | Timezone do horário | `Europe/Lisbon` |
| `RETENTION_DAYS` | Dias de histórico a manter | `90` |

---

## 2) Gerar o APK (app do telemóvel)

O APK é compilado automaticamente pelo **GitHub Actions**: faz push deste repositório e,
no separador **Actions → Build Android APK**, descarrega o artefacto `tracker-apk` (contém
o `app-release.apk` assinado com a chave de debug, suficiente para instalação pessoal).

Para compilar localmente (precisas do Android SDK):

```bash
cd android
./gradlew assembleRelease
# APK em: app/build/outputs/apk/release/app-release.apk
```

### Instalar no telemóvel dela

1. Passa-lhe o `app-release.apk` (cabo, email, Drive…).
2. No Android, permite **instalar de fontes desconhecidas** para essa app.
3. Abre a app **Tracker** e:
   - escreve o **endereço do servidor** (ex.: `https://o-meu-tunel.trycloudflare.com`);
   - escreve o **mesmo `DEVICE_TOKEN`** do servidor;
   - toca **Guardar configuração**;
   - concede as permissões pela ordem indicada: **localização** → escolhe **“Permitir sempre”**
     quando o Android pedir localização em segundo plano → **notificações**;
   - toca **▶ Iniciar partilha**.
4. Para não ser morta pelo sistema, desliga a **otimização de bateria** para a app Tracker
   (Definições → Bateria → a app → “Sem restrições”). O passo exato varia por marca
   (Xiaomi/Samsung/Huawei costumam ser mais agressivos).

A partir daí verás a posição no painel, e podes ajustar intervalo, distância mínima,
escola e horário a qualquer momento — o telemóvel aplica as mudanças no envio seguinte.

---

## Estrutura

```
server/            Servidor Node.js + painel web
  server.js        API (ingestão + painel) e ficheiros estáticos
  public/          index.html + app.js (mapa Leaflet)
android/           App Android (Kotlin, Gradle)
  app/src/main/...
.github/workflows/ Compilação automática do APK
```

## Como funciona o “nas aulas?”

No painel defines a **escola** (clicas no mapa) e o **horário** por dia da semana. O servidor
compara a hora e a posição de cada ponto: dentro do horário e perto da escola → *nas aulas*;
dentro do horário mas longe → aviso.

## Privacidade

Os dados ficam **no teu servidor** e em mais lado nenhum. O histórico é apagado ao fim de
`RETENTION_DAYS`. O painel exige palavra-passe e o envio exige token.
