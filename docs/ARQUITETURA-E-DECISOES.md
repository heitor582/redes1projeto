# Arquitetura e decisões técnicas do NetPulse

Este documento localiza no código os conceitos de programação orientada a objetos (POO), TCP, UDP e multithreading. Os exemplos referem-se à implementação atual do projeto.

## Organização orientada a objetos

O projeto separa responsabilidades em três módulos Maven e organiza as classes por pacote dentro de cada módulo:

- `netpulse-common`: tipos de domínio e protocolo compartilhados entre cliente e servidor.
- `netpulse-server.metrics`: leitura de métricas e seleção da origem real ou simulada.
- `netpulse-server.alerts`: regras, estado ativo e histórico persistente dos alertas.
- `netpulse-server.network`: sockets, sessões e distribuição dos dados.
- `netpulse-client.network`: sockets do cliente e controle de sequência/perda UDP.
- `netpulse-client.ui`: janelas, painéis e gráficos Swing.

As classes encapsulam estados e operações de uma parte do sistema. Por exemplo, `NetPulseServer` controla listeners e sessões; `ClientSession` controla estado, intervalo e sequência de uma conexão; `AlertMonitor` aplica os limites globais; `DashboardPanel` apresenta as informações recebidas. Records como `SystemSnapshot` e `AlertRecord` representam dados imutáveis que passam entre essas responsabilidades.

O contrato `MetricsStrategy` abstrai a leitura das métricas. `OshiMetricsStrategy` representa a coleta real e `SimulatedMetricsStrategy` fornece a origem simulada. `MetricsStrategyFactory` escolhe entre essas origens e valida se o sistema operacional é reconhecido. O OSHI seleciona internamente os adaptadores nativos de acordo com o sistema; a plataforma guardada pela estratégia real é usada apenas para descrever as limitações de sensores no diagnóstico. Isso permite trocar a origem sem misturar a coleta com o protocolo ou com a interface.

O módulo `common` concentra `Metric`, `Mode`, `SystemSnapshot`, `AlertRecord` e `Protocol`. Assim, cliente e servidor interpretam a mesma representação de mensagens e dados sem depender diretamente um do outro.

## Onde e por que TCP é usado

O TCP é usado para a conexão de controle entre cliente e servidor:

- handshake e identificação da sessão (`HELLO`/`WELCOME`);
- comandos como `START`, `STOP`, `SET_INTERVAL`, `SET_THRESHOLD` e consultas de histórico;
- respostas correlacionadas por `requestId` (`RES`);
- mudanças de configuração, alertas e recuperações enviados aos clientes conectados.

No servidor, `NetPulseServer` aceita conexões TCP e cria uma `ClientSession` para cada cliente. No cliente, `NetPulseConnection` mantém a conexão e correlaciona respostas com requisições pendentes.

TCP foi escolhido para essas mensagens porque comandos e transições de alerta precisam chegar em ordem e ser confirmados pela aplicação. TCP fornece um fluxo ordenado e confiável enquanto a conexão está ativa. O protocolo acrescenta mensagens delimitadas por linha e IDs de requisição; a confirmação da aplicação só é exibida depois da resposta correspondente. Isso não garante que o cliente saiba o resultado se a conexão cair durante o processamento.

## Onde e por que UDP é usado

O UDP é usado para registro do endereço de telemetria e para enviar snapshots periódicos:

- o cliente envia `REGISTER` e recebe `REGISTERED`;
- o servidor envia mensagens `METRIC` com CPU, RAM e temperatura disponível;
- cada snapshot inclui uma sequência para que o cliente descarte dados repetidos ou antigos.

O envio e a recepção UDP ficam em `NetPulseServer` e `NetPulseConnection`. O registro informa ao servidor o endereço e a porta UDP de origem observados, que podem ser diferentes do endpoint TCP.

UDP foi escolhido para snapshots porque cada mensagem contém uma leitura completa. Se uma amostra se perder, a próxima tende a ser mais útil que a retransmissão de uma leitura antiga. O sistema prioriza a informação recente e não retransmite snapshots. Como consequência, pode haver perda, duplicação ou desordem; o cliente valida a sequência e sinaliza quando deixa de receber telemetria. Essa escolha não significa que UDP tenha menor latência em qualquer rede.

## Onde e por que há multithreading

No servidor, tarefas separadas evitam que uma atividade bloqueante paralise as demais:

- um loop aceita conexões TCP;
- outro loop recebe registros UDP;
- um executor coleta métricas a cada 500 ms;
- outro distribui snapshots às sessões elegíveis;
- handlers processam clientes, e writers dedicados serializam a saída TCP de cada sessão;
- um executor persiste o histórico sem bloquear a coleta.

No cliente, leitores TCP e UDP recebem dados em paralelo, um writer envia comandos TCP e um scheduler renova o registro UDP e verifica a saúde da conexão. O trabalho de rede fica fora da Event Dispatch Thread do Swing; as atualizações visuais são encaminhadas para a thread da interface.

O multithreading permite que a coleta continue enquanto clientes enviam comandos ou a rede espera por dados. Pools e filas têm limites para restringir o número de tarefas e o acúmulo de mensagens. Cada sessão tem estado próprio, enquanto métricas e alertas configurados são compartilhados no servidor.

## Referências no código

| Conceito | Classes principais |
|---|---|
| TCP no servidor | `NetPulseServer`, `ClientSession` |
| TCP/UDP no cliente | `NetPulseConnection`, `TelemetryTracker` |
| Protocolo compartilhado | `Protocol`, `SystemSnapshot`, `AlertRecord` |
| POO e Strategy | `MetricsStrategy`, `OshiMetricsStrategy`, `MetricsStrategyFactory`, `SimulatedMetricsStrategy` |
| Alertas globais | `AlertMonitor`, `AlertState`, `AlertHistory` |
| Interface e EDT | `DashboardFrame`, `DashboardPanel`, `AlertCenterPanel` |

## Compatibilidade de plataformas

Windows e Linux são as plataformas principais previstas para execução. Java e OSHI fornecem uma base comum, mas disponibilidade de sensores, permissões, firewall e comportamento do sistema operacional ainda precisam ser verificados em cada ambiente. O macOS é um ambiente adicional; sua identificação existe para diagnóstico, mas não substitui a validação em Windows e Linux.
