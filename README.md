# BTC Trading Engine

Bot de trading em tempo real para BTCUSDT, construído em Java 25 com Spring Boot, Binance e PostgreSQL.

O sistema recebe trades da Binance, agrega candles de 15 minutos, calcula indicadores técnicos, produz sinais por regras, executa operações em simulação ou Binance Testnet/real e acompanha risco, persistência e métricas pelo dashboard.

> O predictor atual é determinístico e baseado em regras heurísticas. Os valores `probabilityUp` e `probabilityDown` são probabilidades implícitas pelo score, não probabilidades estatísticas calibradas por machine learning.

## Estado Atual

- Mercado padrão: `BTCUSDT`.
- Timeframe padrão: `15m`.
- Modo padrão: simulação.
- Endpoint Binance padrão: **Testnet** (`stream.testnet.binance.vision` e `testnet.binance.vision`).
- Dashboard web com WebSocket e endpoints REST.
- Histórico inicial carregado pela Binance REST, com fallback para PostgreSQL.
- Candles e ticks persistidos de forma assíncrona.
- Compra e fechamento manual pelo dashboard.
- Target e stop loss avaliados a cada trade recebido, não apenas no fechamento do candle.
- Execução real preparada com reconciliação, confirmação de ordens e User Data Stream.

## Arquitetura

```text
Binance WebSocket (aggTrade)
				|
				v
NormalizedPriceEvent
				|
				+--> DatabaseWriter --> PostgreSQL (ticks)
				|
				+--> CandleAggregator --> CandleEvent
																			|
																			+--> DatabaseWriter --> PostgreSQL (candles)
																			|
																			+--> FeatureExtractor
																								|
																								+--> indicadores/features
																								+--> RuleBasedPredictor
																													|
																													+--> PredictionVector
																													+--> PositionManager
																													+--> Dashboard/WebSocket
```

No modo real, o fluxo adicional é:

```text
API Binance
	+--> saldo USDT
	+--> reconciliação de ordens/posição
	+--> execução de mercado
	+--> confirmação via User Data Stream
```

## Indicadores E Features

Os parâmetros padrão estão em `btctradingengine/src/main/resources/application.properties`.

### Indicadores

- SMA: período `50`.
- EMA: período `26`.
- RSI: período `14`.
- ATR: período `14`.
- MACD: `12/26/9`.

### Features

- Retorno do candle.
- Volatilidade curta e longa.
- Distância do preço para SMA e EMA.
- RSI.
- MACD, sinal e histograma.
- ATR.
- Razão de volume.
- Faixa máxima/mínima.
- Posição do fechamento dentro da faixa.
- Hora e dia da semana.

O histórico de candles fechados é usado para aquecer os indicadores antes do WebSocket começar a operar. O warmup atualiza as métricas, mas não gera ordens históricas.

## Predictor E Score

O `RuleBasedPredictor` combina cinco regras:

- RSI.
- Distância da SMA.
- MACD normalizado por ATR.
- Volatilidade ATR.
- Razão de volatilidade curta/longa.

Cada regra retorna um score entre `-1` e `+1`. O score final é a média:

```text
sumScore = scoreRSI + scoreSMA + scoreMACD + scoreATR + scoreVolatilidade
avgScore = sumScore / quantidadeDeRegras
confidence = abs(avgScore)
```

A probabilidade exibida é derivada pela sigmoide:

```text
probabilityUp = 1 / (1 + exp(-avgScore * 2))
probabilityDown = 1 - probabilityUp
```

Exemplo:

```text
sumScore = 0.70
avgScore = 0.140
confidence = 0.140
probabilityUp ≈ 0.570
```

Os scores individuais, `sum` e `avg` aparecem na explicação da previsão e no log.

### Sinais

Com a configuração padrão:

```properties
prediction.buy.threshold=0.65
prediction.sell.threshold=-0.65
prediction.hold.min=-0.3
prediction.hold.max=0.3
prediction.confirmation.snapshots=2
```

```text
avgScore >= 0.65  -> BUY
avgScore <= -0.65 -> SELL
qualquer outro    -> HOLD
```

BUY e SELL ainda precisam da quantidade configurada de snapshots consecutivos para confirmação. A zona entre `0.30` e `0.65` continua sendo HOLD, apenas com score positivo mais forte.

## Métricas E Interpretação

### Métricas da previsão

- `probabilityUp`: probabilidade implícita pelo score heurístico.
- `probabilityDown`: complemento da probabilidade de alta.
- `confidence`: magnitude absoluta do score médio.
- `sum`: soma dos scores individuais.
- `avg`: score médio usado pela decisão.

Essas métricas não representam uma taxa histórica de acerto. Para isso ainda é necessário executar backtests e calibrar o modelo com dados fora da amostra.

### Métricas de trading

O dashboard expõe:

- Total de trades.
- Trades vencedores.
- P&L total.
- Sharpe ratio.
- Maximum drawdown.
- Histórico dos últimos trades fechados.

### Métricas do backtest

O `BacktestReport` calcula:

- Total de trades.
- Win rate.
- P&L total líquido.
- Retorno percentual.
- P&L médio.
- Profit factor.
- Maximum drawdown.
- Sharpe ratio.
- Média de candles por trade.

As métricas usam comissão de entrada e saída. A configuração padrão é:

```properties
backtest.commission.rate=0.001
```

O backtest também suporta target e stop por candle, usando máxima e mínima. Quando os dois limites aparecem no mesmo candle, o stop tem prioridade, evitando um resultado artificialmente otimista.

## Risco E Saídas

```properties
trading.target.percent=2.0
trading.stop.loss.percent=1.5
trading.max.drawdown.percent=5
```

Uma posição pode ser fechada por:

- Target atingido.
- Stop loss atingido.
- Reversão do sinal.
- Fechamento manual.
- Falha de ordem.
- Erro de execução.

O target e o stop são verificados a cada `aggTrade`. No dashboard, os preços absolutos de venda automática e stop são mostrados com base no preço de entrada.

## Dashboard

O dashboard mostra:

- Preço e gráfico de candles.
- Feed de trades e latência.
- OHLCV.
- Indicadores e features.
- Sinal atual, score e probabilidades.
- Posição aberta, P&L, target e stop.
- Compra manual.
- Fechamento manual.
- Trades recentes.
- Resumo de estatísticas.

Endpoints principais:

```text
GET  /api/candles/history
GET  /api/metrics/current
GET  /api/prediction/current
GET  /api/trades/open
GET  /api/trades/closed
GET  /api/stats
POST /api/trades/manual/buy
POST /api/trades/manual/close
WS   /ws/live
```

## Persistência

O PostgreSQL armazena:

- Candles agregados na tabela `candles`.
- Trades brutos na tabela `ticks`.
- Operações na tabela `trades`.
- Eventos de execução na tabela `execution_log`.

Candles são persistidos de forma idempotente por símbolo e horário de abertura. Operações são salvas imediatamente na entrada e atualizadas no fechamento com `UPSERT`. O histórico fechado e, em simulação, a posição aberta podem ser restaurados após reinício.

## Configuração

Arquivo principal:

```text
btctradingengine/src/main/resources/application.properties
```

### Mercado

```properties
market.symbol=BTCUSDT
market.interval.seconds=900
market.binance.interval=15m
market.history.candles=200
```

### Binance

O projeto está configurado por padrão para Binance Testnet:

```properties
binance.ws.url=wss://stream.testnet.binance.vision:9443/ws/
binance.rest.url=https://testnet.binance.vision
```

Credenciais podem ser fornecidas por variáveis de ambiente:

```text
POLYMARKET_BINANCE_API_KEY
POLYMARKET_BINANCE_API_SECRET
```

Não coloque credenciais reais no Git.

### Banco

```properties
db.url=jdbc:postgresql://localhost:5432/polymarket_btc
db.user=polymarket
db.password=senha@123
```

Para produção, prefira `POLYMARKET_DB_PASSWORD` ou outro mecanismo externo de secrets.

## Inicialização

Pré-requisitos:

- JDK 25.
- Maven.
- Docker e Docker Compose.
- PostgreSQL local ou acessível pela URL configurada.

Subir o PostgreSQL:

```bash
docker compose up -d postgres
```

Executar os testes:

```bash
cd btctradingengine
mvn test
```

Executar a aplicação:

```bash
cd btctradingengine
mvn spring-boot:run -Dspring-boot.run.main-class=dev.romeo.btctradingengine.Main
```

O dashboard Spring Boot fica disponível, por padrão, em:

```text
http://localhost:8080
```

## Simulação E Trading Real

O modo padrão é simulação:

```properties
trading.real.enabled=false
```

Nesse modo, compras e vendas alteram apenas o estado interno, o banco e o dashboard.

Para habilitar execução real/testnet:

```properties
trading.real.enabled=true
```

Antes de ativar:

1. Use credenciais da Binance Testnet.
2. Confirme que a URL REST e WebSocket apontam para o mesmo ambiente.
3. Verifique saldo USDT e permissões da chave.
4. Confirme os filtros de quantidade e notional do símbolo.
5. Teste primeiro com capital pequeno.

Quando o modo real é iniciado, o sistema:

- Consulta o saldo USDT.
- Usa o saldo real no controle de risco.
- Reconcilia ordens/posição com a Binance.
- Envia ordens de mercado.
- Confirma execuções pelo User Data Stream.
- Usa polling como fallback de confirmação.
- Não restaura automaticamente uma posição simulada local.

## Backtest

O replay reutiliza o pipeline de features e as mesmas regras do modo ao vivo:

```text
BacktestRunner
	-> FeatureExtractor
	-> RuleBasedPredictor
	-> BacktestEngine
	-> BacktestReport
```

O `BacktestEngine.configured()` usa os valores do `application.properties`. O motor considera comissão, target, stop e fechamento por sinal oposto.

O backtest deve ser interpretado com cuidado: resultados passados não garantem performance futura. Ainda é necessário avaliar slippage, spread, liquidez, calibração, divisão temporal e validação fora da amostra.

## Limitações Conhecidas

- O predictor ainda não é machine learning calibrado.
- A probabilidade é heurística e muda principalmente no fechamento do candle.
- O sistema atual opera um símbolo por processo.
- Execução real exige validação adicional dos filtros da Binance.
- Slippage e spread ainda precisam ser modelados com mais precisão.
- O modo real depende de credenciais e saldo válidos.
- Não há garantia de lucro; o projeto é experimental.

## Estrutura Principal

```text
btctradingengine/src/main/java/dev/romeo/btctradingengine/
├── adapter/       # Binance, WebSocket, candles e event bus
├── backtest/      # Replay, engine, relatório e validação
├── config/        # Leitura e validação das propriedades
├── dashboard/     # REST, WebSocket e estado da interface
├── feature/       # Buffer, extractor e vetor de features
├── indicator/     # SMA, EMA, RSI, ATR e MACD
├── marketstate/   # Estado quente de mercado
├── persistence/   # PostgreSQL, leitura e gravação assíncrona
├── prediction/    # Predictor, sinais e regras
└── trading/       # Posições, risco, ordens e reconciliação
```

## Aviso

Este projeto pode enviar ordens reais quando `trading.real.enabled=true`. Mantenha essa opção desativada até validar o backtest, as credenciais, os filtros de quantidade, o saldo e o comportamento de reconciliação. O uso é de responsabilidade do operador.
