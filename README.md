![Java 25](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?style=flat-square&logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)
![Binance API](https://img.shields.io/badge/Binance-Testnet%2FReal-yellow?style=flat-square&logo=binance)

Bot de alta performance para trading em tempo real no par **BTCUSDT**. O sistema processa trades via WebSocket da Binance, agrega candles de 15 minutos, calcula indicadores técnicos avançados, produz sinais heurísticos e executa ordens simuladas ou reais na Binance Testnet/Mainnet com monitoramento contínuo de risco e dashboard em tempo real.

> **Nota sobre o Predictor:** O motor preditivo atual é determinístico e baseado em regras heurísticas. Os valores `probabilityUp` e `probabilityDown` representam probabilidades implícitas derivadas do score técnico, e não probabilidades estatísticas calibradas por machine learning.
---

## Estado Atual do Sistema

| Parâmetro / Recurso | Configuração Padrão / Estado |
| :--- | :--- |
| **Mercado & Timeframe** | `BTCUSDT` \| `15m` |
| **Modo de Operação** | Simulação (Paper Trading) |
| **Endpoints Binance** | Testnet (`stream.testnet.binance.vision` e `testnet.binance.vision`) |
| **Persistência** | PostgreSQL (gravação assíncrona de ticks, candles e ordens) |
| **Interface** | Dashboard Web dinâmico via WebSocket + Endpoints REST |
| **Gestão de Posição** | Target e Stop Loss avaliados tick-a-tick (`aggTrade`), não só no fechamento |
| **Execução Real** | Suporte a reconciliação, confirmação de ordens e User Data Stream |

---

## Arquitetura do Fluxo de Dados

### 1. Processamento em Tempo Real
```text
Binance WebSocket (aggTrade)
         │
         ▼
NormalizedPriceEvent ───┬───► DatabaseWriter ─────► PostgreSQL (ticks)
                        │
                        └───► CandleAggregator ───► CandleEvent
                                                        │
                                                        ├───► DatabaseWriter ───► PostgreSQL (candles)
                                                        │
                                                        └───► FeatureExtractor
                                                                    │
                                                                    ├───► Indicadores / Features
                                                                    └───► RuleBasedPredictor
                                                                                │
                                                                                └───► PredictionVector
                                                                                        ├───► PositionManager
                                                                                        └───► Dashboard (WS)
```

### 2. Execução Real (Modo Habilitado)
```text
API Binance REST / WS
    ├──► Consulta Saldo USDT em Tempo Real
    ├──► Reconciliação de Posição & Ordens
    ├──► Execução de Mercado (Market Orders)
    └──► Confirmação via User Data Stream (com fallback via Polling)
```

---

## Indicadores e Features

O histórico inicial é carregado via Binance REST (com fallback para o PostgreSQL) para aquecer os indicadores antes da abertura do WebSocket.

### Indicadores Técnicos

| Indicador | Parâmetro Padrão |
| :--- | :--- |
| **SMA** (Média Móvel Simples) | Período `50` |
| **EMA** (Média Móvel Exponencial) | Período `26` |
| **RSI** (Índice de Força Relativa) | Período `14` |
| **ATR** (Average True Range) | Período `14` |
| **MACD** | Fast `12`, Slow `26`, Signal `9` |

### Pipeline de Features Extraídas
* **Retorno e Volatilidade:** Retorno do candle, volatilidade de curto e longo prazo.
* **Médias & Osciladores:** Distância do preço para SMA/EMA, RSI, MACD (Linha, Sinal e Histograma), ATR.
* **Estrutura de Mercado:** Razão de volume, faixa máxima/mínima, posição do fechamento na faixa.
* **Sazonalidade:** Hora do dia e dia da semana.

---

## Predictor e Tomada de Decisão

O `RuleBasedPredictor` combina cinco regras individuais. Cada regra gera um score no intervalo $[-1, +1]$:

1. **RSI Score**
2. **SMA Distance Score**
3. **MACD Score** *(normalizado por ATR)*
4. **ATR Volatility Score**
5. **Short/Long Volatility Ratio Score**

### Cálculo do Score e Probabilidade Implícita

```text
sumScore    = scoreRSI + scoreSMA + scoreMACD + scoreATR + scoreVolatilidade
avgScore    = sumScore / 5
confidence  = |avgScore|

probabilityUp   = 1 / (1 + exp(-avgScore * 2))
probabilityDown = 1 - probabilityUp
```

### Regras de Entrada e Sinais

| Condição | Sinal Gerado | Confirmação Necessária |
| :--- | :--- | :--- |
| `avgScore >= 0.65` | **BUY** | $N$ snapshots consecutivos (`prediction.confirmation.snapshots`) |
| `avgScore <= -0.65` | **SELL** | $N$ snapshots consecutivos (`prediction.confirmation.snapshots`) |
| Outros valores | **HOLD** | Imediato (Zona neutra/espera) |

---

## Métricas do Dashboard e Backtest

### Métricas de Predição & Mercado
* `probabilityUp` / `probabilityDown`: Probabilidades implícitas heurísticas.
* `confidence`: Intensidade absoluta do score médio.
* **Dashboard Live:** Total de trades, Win Rate, P&L Total, Sharpe Ratio, Max Drawdown e histórico recente.

### Relatório de Backtest (`BacktestReport`)
O motor de replay valida a estratégia sobre dados históricos considerando comissões e gestão intra-candle:
* Win Rate, Profit Factor, P&L Líquido Total e Retorno %.
* Maximum Drawdown e Sharpe Ratio.
* Média de candles por trade.
* **Tratamento de Conflitos:** Se Target e Stop Loss forem atingidos no mesmo candle (usando High/Low), o **Stop Loss tem prioridade automatizada**.

---

## Gestão de Risco e Saídas

```properties
trading.target.percent=2.0
trading.stop.loss.percent=1.5
trading.max.drawdown.percent=5.0
```

Uma posição é encerrada automaticamente sob as seguintes condições:
* Target de Lucro atingido
* Stop Loss atingido
* Reversão do sinal preditivo
* Intervenção manual via Dashboard
* Falha na execução da ordem

---

## Dashboard & API REST

O sistema provê uma interface web dinamicamente atualizada via WebSocket (`/ws/live`).

### Endpoints REST

| Método | Endpoint | Descrição |
| :--- | :--- | :--- |
| `GET` | `/api/candles/history` | Histórico dos últimos candles agregados |
| `GET` | `/api/metrics/current` | Métricas de performance e runtime |
| `GET` | `/api/prediction/current` | Sinal preditivo, probabilidades e scores |
| `GET` | `/api/trades/open` | Detalhes da posição atualmente aberta |
| `GET` | `/api/trades/closed` | Histórico de operações encerradas |
| `GET` | `/api/stats` | Estatísticas acumuladas da conta/estratégia |
| `POST` | `/api/trades/manual/buy` | Executa ordem de compra manual |
| `POST` | `/api/trades/manual/close` | Força o fechamento da posição aberta |

---

## Modelo de Persistência

O PostgreSQL armazena o histórico do sistema com suporte a `UPSERT` e recuperação de estado após reinício:

* `candles`: Agregação idempotente por símbolo e timestamp de abertura.
* `ticks`: Registro de trades brutos recebidos da exchange.
* `trades`: Operações abertas e fechadas (mantedas atualizadas em tempo real).
* `execution_log`: Rastreabilidade de eventos de ordens e integrações.

---

## Configuração do Sistema

As configurações estão centralizadas em `btctradingengine/src/main/resources/application.properties`.

```properties
# Mercado
market.symbol=BTCUSDT
market.interval.seconds=900
market.binance.interval=15m
market.history.candles=200

# Binance Testnet
binance.ws.url=wss://stream.testnet.binance.vision:9443/ws/
binance.rest.url=https://testnet.binance.vision

# Banco de Dados
db.url=jdbc:postgresql://localhost:5432/polymarket_btc
db.user=polymarket
db.password=senha@123
```

> **Variáveis de Ambiente Recomendadas:**
> Setar `POLYMARKET_BINANCE_API_KEY`, `POLYMARKET_BINANCE_API_SECRET` e `POLYMARKET_DB_PASSWORD` para evitar expor credenciais no repositório.

---

## Como Executar

### Pré-requisitos
* **Java SDK 25**
* **Apache Maven**
* **Docker & Docker Compose**

### Passo a Passo

1. **Subir o Banco de Dados (PostgreSQL):**
   ```bash
   docker compose up -d postgres
   ```

2. **Executar os Testes Unitários:**
   ```bash
   cd btctradingengine
   mvn test
   ```

3. **Iniciar a Aplicação:**
   ```bash
   cd btctradingengine
   mvn spring-boot:run -Dspring-boot.run.main-class=dev.romeo.btctradingengine.Main
   ```

4. **Acessar o Dashboard:**
   Abra no navegador: `http://localhost:8080`

---

## Simulação vs. Trading Real

Por padrão, a aplicação roda em modo **Simulação** (`trading.real.enabled=false`).

Para ativar operações com dinheiro real ou na Testnet da Binance:
1. Altere no `application.properties`:
   ```properties
   trading.real.enabled=true
   ```
2. Forneça as credenciais válidas da Binance API.
3. Certifique-se de ter saldo em USDT disponível.
4. Verifique as regras de notional mínimo e lot size do par `BTCUSDT`.

---

## Estrutura do Projeto

```text
btctradingengine/src/main/java/dev/romeo/btctradingengine/
├── adapter/         # Conectores WebSocket Binance, REST e Event Bus
├── backtest/        # Engine de Replay, Validação e Relatórios
├── config/          # Leitura e Validação de Propriedades do Spring
├── dashboard/       # Controllers REST, Handlers WS e Estado da UI
├── feature/         # Feature Extractor, Buffers e Vetores de Entrada
├── indicator/       # Implementação dos Indicadores (SMA, RSI, ATR, MACD)
├── marketstate/     # Estado em memória do mercado em tempo real
├── persistence/     # DAO e Entidades PostgreSQL (Gravação Assíncrona)
├── prediction/      # Engine de Regras Heurísticas e Sinais
└── trading/         # Gerenciamento de Posições, Ordens e Risco
```

---