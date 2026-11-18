# Protocolo NetPulse v1

## Enquadramento e tipos

TCP persistente, UTF-8 estrito, uma mensagem por linha, terminada apenas por LF (`\n`). Limite: **4096 bytes antes do LF**. CR, NUL e demais controles ASCII (0–31, 127) são proibidos no conteúdo; CRLF não é aceito. EOF no meio de uma linha, UTF-8 malformado ou linha excessiva encerram o fluxo TCP. Não há serialização de objetos Java.

O separador é `|`. Campos vazios e campos extras são rejeitados. Não há escape: nenhum valor pode conter `|` ou quebra de linha. Comandos/enums são ASCII, sensíveis a maiúsculas; o enquadramento suporta UTF-8, mas a gramática de cada comando restringe os valores aceitos. Números decimais usam ponto e `Locale.ROOT`; não aceitam vírgula, expoente, `NaN`, infinito ou sinal `+`. Inteiros positivos são representáveis em `long`, com até 19 dígitos. Portas configuráveis: 1–65535; portas 0 são usadas apenas na API de testes para alocação dinâmica.

UUID é a forma canônica minúscula com 36 caracteres. Modo: `REAL` ou `SIMULATED`. Plataforma: `Windows`, `Linux`, `macOS` ou `Unsupported` (possível em simulação). Não existe texto livre enviado pelo usuário no protocolo v1.

## Sessão TCP

```text
Cliente                        Servidor
HELLO|1                    ->  valida versão em até 5 segundos
                           <-  WELCOME|<uuid>|<mode>|<platform>
REGISTER|<uuid> (UDP)       ->  registra origem do datagrama
                           <-  REGISTERED|<uuid> (UDP)
REQ|1|START                 ->  muda somente a sessão
                           <-  RES|1|OK|STATE=STARTED
                           <-  METRIC|... (UDP periódico)
                           <-  ALERT|... (TCP assíncrono)
REQ|2|STOP                  ->  pausa somente a sessão
                           <-  RES|2|OK|STATE=STOPPED
```

Handshake inválido: `ERROR|INVALID_HANDSHAKE`, seguido de fechamento. Há no máximo 16 conexões atendidas (incluindo handshake); excedentes são fechadas. O UUID existe apenas enquanto a conexão TCP correspondente estiver ativa. A desconexão detectada remove a sessão e impede novo registro/transmissão. Uma interrupção silenciosa de rede pode demorar a ser detectada pelo TCP; não há heartbeat de aplicação v1.

### Estados

```text
DISCONNECTED -- TCP + HELLO válido --> STOPPED
STOPPED -- START confirmado --> STARTED
STARTED -- STOP confirmado --> STOPPED
qualquer estado -- EOF/erro/fechamento --> DISCONNECTED
```

Registro UDP é um eixo independente: `UNREGISTERED` ou `REGISTERED`. START sem registro é válido e aguarda endpoint para transmitir telemetria. Alertas globais independem de START/STOP. Re-registro altera somente endpoint, nunca START/STOP, sequência, intervalo ou limites. START e STOP são idempotentes quanto ao estado. START força elegibilidade para próximo envio; não zera sequência. A aplicação cliente só atualiza estado de comando após `RES ... OK`.

### Requisições e respostas

`REQ|<requestId>|<command>[|argumentos]`, com requestId positivo. O cliente oficial usa IDs crescentes e não reutiliza IDs durante a conexão. O servidor devolve exatamente o ID recebido; não mantém cache para deduplicação de comandos TCP.

| Comando | Argumentos e validação | Campo final de `RES|id|OK|...` |
|---|---|---|
| START | nenhum | `STATE=STARTED` |
| STOP | nenhum | `STATE=STOPPED` |
| SET_INTERVAL | inteiro 250–5000 ms | `INTERVAL=1000` |
| SET_THRESHOLD | CPU ou RAM, decimal 0–100 | `THRESHOLD=CPU,80.00` |
| SET_THRESHOLD | TEMP, decimal 20–100°C | `THRESHOLD=TEMP,75.00` |
| GET_STATUS | nenhum | estado completo descrito abaixo |
| GET_ACTIVE_ALERTS | nenhum | `ACTIVE=<quantidade>` após linhas ENTRY |
| GET_ALERTS | beforeId ≥ 0; limit 1–50 | `HISTORY=<quantidade>` após linhas ENTRY |

Padrões de sessão: STOPPED e intervalo 1000 ms. Limites globais iniciais: CPU 80%, RAM 85%, temperatura 75°C; limites persistidos substituem esses defaults na reinicialização. SET_THRESHOLD altera a regra global, não somente a sessão. Exemplo de GET_STATUS:

```text
REQ|7|GET_STATUS
RES|7|OK|STATE=STOPPED;INTERVAL=1000;CPU=80.00;RAM=85.00;TEMP=75.00;UDP=REGISTERED
```

GET_STATUS informa configuração, não valores das métricas; estes chegam no snapshot UDP. `UDP=REGISTERED` no servidor significa que há endpoint conhecido, sem provar recebimento atual no cliente.

Erro: `RES|id|ERROR|<codigo>`. Se o requestId não puder ser extraído/validado, usa-se **0**, reservado para erro não correlacionável. O cliente oficial, que nunca envia ID inválido, trata resposta com 0 como violação de protocolo. A conexão pode continuar após requisições inválidas dentro do limite de enquadramento.

| Código | Condição |
|---|---|
| INVALID_REQUEST | prefixo diferente de REQ ou cabeçalho incompleto |
| INVALID_MESSAGE | campo vazio, controle ou conteúdo inválido |
| INVALID_FIELDS | quantidade incorreta de campos |
| INVALID_NUMBER | formato numérico não permitido/número positivo exigido |
| INVALID_INTERVAL | intervalo fora de 250–5000 |
| INVALID_THRESHOLD | limite fora da faixa |
| INVALID_HISTORY_LIMIT | tamanho da página fora de 1–50 |
| UNKNOWN_COMMAND | comando não reconhecido |
| INVALID_VALUE | enum desconhecido ou conversão numérica inválida/overflow |

`INVALID_UTF8`, `MESSAGE_TOO_LONG` e `TRUNCATED_MESSAGE` são falhas de enquadramento detectadas localmente; o servidor fecha a conexão sem prometer uma resposta. O cliente usa timeout de conexão e handshake de 4 s e de confirmação de comando de aproximadamente 5 s (varredura a cada segundo). Timeout de comando encerra a conexão para evitar apresentar um estado de execução incerto como confirmado. Não há repetição automática de comandos.

### Eventos de alerta

```text
ALERT|<timestampEpochMillis>|CPU|85.20|80.00
RECOVERY|<timestampEpochMillis>|CPU|76.00
```

ALERT tem cinco campos; RECOVERY tem quatro. Métrica: CPU, RAM ou TEMP. Timestamp é o do snapshot avaliado, não o de recebimento. Eventos e respostas compartilham um único escritor por sessão; não há intercalação de bytes. Eventos podem aparecer entre respostas, por isso o cliente mantém somente um leitor contínuo de TCP e usa requestId para correlação.

O servidor mantém um latch global por métrica. Quando inativo, valor `>= limite` emite ALERT a todos os clientes conectados e ativa o latch. Ativo, emite RECOVERY quando valor `<= limite − 2`. A avaliação é única por snapshot, mesmo sem clientes; o evento é salvo uma única vez. Alterar limite reavalia o último snapshot. START/STOP controlam somente UDP e não suspendem alertas globais. `NA` preserva o latch, sem emitir recuperação falsa.

Toda alteração válida de regra também emite `CONFIG|<CPU/RAM/TEMP>|<limite>` a todos os clientes conectados. CONFIG e eventos podem aparecer antes da confirmação RES da alteração; o cliente solicitante aguarda sua RES para confirmar o comando. Se houver alterações concorrentes, a última processada prevalece. O cliente consulta GET_STATUS ao conectar para carregar os limites vigentes. Não há permissões diferentes entre clientes.

### Consulta de ativos e histórico

```text
REQ|8|GET_ACTIVE_ALERTS
ENTRY|8|42|1794560000000|<serverRunUuid>|REAL|ALERT|RAM|72.00|70.00
RES|8|OK|ACTIVE=1

REQ|9|GET_ALERTS|0|50
ENTRY|9|43|1794560010000|<serverRunUuid>|REAL|RECOVERY|RAM|68.00|70.00
ENTRY|9|42|1794560000000|<serverRunUuid>|REAL|ALERT|RAM|72.00|70.00
RES|9|OK|HISTORY=2
```

ENTRY possui dez campos: prefixo, requestId, ID global do evento, timestamp, UUID da execução do servidor, modo, tipo, métrica, valor e limite no momento do evento. O UUID de execução não é o UUID de sessão TCP. IDs são positivos, crescentes e preservados após reinício. `ALERT` e `RECOVERY` são os únicos tipos. Valores são validados pelas mesmas faixas das métricas e dos limites. A serialização preserva decimais sem notação exponencial.

GET_ALERTS retorna do mais recente ao mais antigo. Cursor `beforeId=0` seleciona os eventos mais recentes; um cursor positivo seleciona IDs estritamente menores que ele. Para a próxima página, use o menor ID recebido. Cada consulta devolve um snapshot imutável da página; novos eventos não mudam essa resposta. A retenção de 1000 eventos pode remover eventos antigos entre consultas. Resposta vazia é válida.

GET_ACTIVE_ALERTS retorna até três eventos ALERT ainda ativos, um por métrica, sem ordenar por ID. O registro identifica o disparo original, incluindo seu valor/limite naquele momento; GET_STATUS fornece a configuração atual. Indisponibilidade do sensor não encerra um alerta existente. Após reiniciar o servidor, ativos são reavaliados a partir de novas amostras; não são restaurados como se fossem leituras atuais.

Uma resposta de consulta consiste em zero ou mais ENTRY e um RES com a contagem final. Todas as linhas usam o mesmo requestId; eventos ao vivo podem intercalar. O cliente só publica a lista depois de validar contagem e confirmação, evitando listas parciais. O limite de 50 entradas mantém as páginas abaixo da capacidade da fila TCP por sessão. A lista recente é carregada ao conectar, após eventos e por atualização manual.

### Persistência no servidor

`--history-file` seleciona o arquivo (padrão `data/alerts.log`). A primeira linha `RULES|<cpu>|<ram>|<temp>` guarda configuração; as demais usam os oito campos de evento após requestId. Mantêm-se até 1000 registros. Arquivo corrompido impede a abertura e não é sobrescrito silenciosamente. Um lock de arquivo impede dois escritores para o mesmo caminho.

Um executor dedicado acrescenta eventos ao arquivo quando há mudanças, sem escrita em disco dentro da coleta/locks de sessão. Alterações de regras e compactação do journal substituem uma cópia temporária. A compactação ocorre ao passar de aproximadamente 1250 entradas, preservando as 1000 mais recentes. O executor verifica mudanças a cada 500 ms. O encerramento normal tenta salvar as últimas alterações. Existe janela de perda em encerramento abrupto, e o SO pode manter buffers de disco; não há garantia de durabilidade transacional. Erros de gravação são registrados e retentados, enquanto a consulta em memória continua disponível.


## UDP

Um único socket UDP do cliente envia registros e recebe respostas e snapshots. O servidor usa um único socket UDP para todos os clientes. Datagrama é uma mensagem completa **sem LF**, com no máximo **1199 bytes**, para permanecer abaixo de 1200. Datagrama maior é descartado; a recepção reserva um byte adicional para detectar excesso.

```text
REGISTER|<uuid>
REGISTERED|<uuid>
METRIC|<uuid>|<sequence>|<timestampEpochMillis>|<mode>|<cpu>|<ram>|<temperature>
```

O servidor aceita REGISTER apenas para sessão TCP ativa e memoriza endereço e porta **de origem**, sem inferir o endpoint pelo socket TCP. Registros inválidos ou de UUID desconhecido são ignorados, sem resposta. REGISTERED é enviado ao endpoint observado. O cliente repete REGISTER a cada 10 s; ausência de confirmação inicial após 4 s é sinalizada, e confirmação antiga por mais de 25 s deixa o estado não registrado. A renovação tenta manter/atualizar o mapeamento NAT, sem garantir sua travessia.

Exemplo de snapshot:

```text
METRIC|3f19e739-b382-4c17-9b46-62172349c640|21|1794560000000|REAL|42.10|58.00|NA
```

`sequence`: positivo, crescente por sessão, incrementado em cada tentativa de envio. Pode haver lacunas por perda ou erro de envio. Timestamp: epoch Unix em milissegundos. CPU e RAM: 0–100. Temperatura disponível: maior que zero e até 150°C; faixa de plausibilidade conservadora, distinta da faixa de configuração de limites (20–100°C). Temperatura simulada: 20–100°C. `NA` representa **UNAVAILABLE** em qualquer métrica e jamais equivale a zero. CPU/RAM reais iguais a zero são válidas; temperatura zero é indisponível. CPU não tem valor durante a primeira amostra.

O cliente valida comprimento, UTF-8, gramática, UUID, modo e faixas antes de usar dados. O socket UDP restringe a origem ao endereço/porta configurados do servidor, sem criar confiabilidade de transporte. Sequência menor ou igual à última válida é descartada, assim como UUID ou modo diferentes. Não há retransmissão, ACK por snapshot ou reordenação em buffer. Pacotes recebidos enquanto STOPPED não atualizam gráficos.

A perda de telemetria é sinalizada somente durante STARTED, após `max(4000 ms, 3 × intervalo)` sem snapshot novo válido. O cálculo usa `System.nanoTime()` local; não depende de relógios sincronizados. Antes do primeiro snapshot, conta-se a partir da confirmação de START. Duplicatas não renovam esse prazo. Intervalos de transmissão menores que os 500 ms da coleta podem apresentar o mesmo timestamp com sequências novas.

## Limites operacionais e de segurança

Filas TCP: 128 mensagens de saída por sessão; fila cheia fecha o cliente lento. Um writer dedicado por sessão limita a interferência de sockets bloqueados, com pool de 16 e fila global limitada. Cliente: até 64 comandos pendentes, um escritor, dois leitores e um scheduler; rede nunca executa na EDT. O fechamento de sockets desbloqueia leituras e o shutdown interrompe executores.

O UUID é um token de associação sem criptografia ou autenticação forte. Quem o observar pode tentar sequestrar o endpoint UDP. Não há proteção contra atacante ativo, criptografia, autorização, mitigação completa de negação de serviço ou autenticação do servidor. A v1 destina-se a laboratório confiável; limites e validação reduzem erros e consumo, mas não tornam o protocolo adequado à Internet pública.
