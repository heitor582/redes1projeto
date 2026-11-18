# NetPulse — Sistema Multiplataforma de Monitoramento Remoto em Tempo Real

**Integrantes:** [preencher nomes e matrículas]  
**Disciplina:** [preencher]  
**Professor:** [preencher]  
**Instituição/Turma:** [preencher]  
**Data da entrega:** [preencher]  
**Versão técnica:** 1.0.0 — validação local em 13 de novembro de 2026.

## 1. Resumo

Este trabalho apresenta o NetPulse, uma aplicação cliente-servidor para acompanhamento remoto de recursos de um computador. O servidor coleta utilização global de CPU, ocupação de memória física e temperatura da CPU quando o sensor está acessível. Os dados são organizados em snapshots imutáveis e compartilhados com múltiplos clientes desktop. TCP transporta comandos, confirmações e eventos de alerta; UDP transporta amostras periódicas cujo valor informativo diminui com o tempo. A implementação utiliza Java, Swing, Java2D, sockets nativos, executores concorrentes e OSHI baseado em JNA. O padrão Strategy separa o contrato de coleta das particularidades de plataforma e de uma fonte simulada explicitamente identificada. Testes automatizados locais verificam protocolo, isolamento de sessões e encerramento. A validação por roteadores físicos, as capturas de tráfego e a avaliação em outras plataformas não foram realizadas nesta etapa.

**Palavras-chave:** redes de computadores; TCP; UDP; monitoramento; concorrência; Strategy.

## 2. Introdução e objetivos

O acompanhamento dos recursos de um computador remoto permite identificar situações de alta utilização sem manter acesso interativo ao sistema operacional monitorado. Esse problema também oferece um caso concreto para comparar necessidades de comunicação: uma ordem de parada deve ser processada de maneira confiável e confirmada; uma leitura antiga de CPU pode perder relevância antes de ser recuperada após uma perda de pacote.

O objetivo geral é implementar uma aplicação de monitoramento remoto que demonstre o uso complementar de TCP e UDP. São objetivos específicos: coletar métricas reais da máquina; permitir vários clientes sem multiplicar o custo de leitura do hardware; manter intervalos de telemetria independentes e regras de alerta globais; distinguir informação indisponível de valor numérico zero; exibir gráficos e alertas sem bloquear a interface; e fornecer uma implementação verificável por testes reproduzíveis. “Tempo real” é empregado no sentido de atualização contínua para acompanhamento humano, sem garantia de prazo determinístico ou de sistema de tempo real estrito.

## 3. Requisitos e situação de implementação

| ID | Requisito | Implementação e verificação |
|---|---|---|
| RF01 | Coleta global CPU/RAM | OSHI, adapter testável; teste com hardware fake e execução real local |
| RF02 | Temperatura opcional | `null`/UNAVAILABLE, `NA` no protocolo; testes de sensor inválido e retorno |
| RF03 | Real e simulado | Strategy + factory; testes de plataforma e seed |
| RF04 | Múltiplos clientes | Limite de 16; integração com três clientes ativos e cinco conectados |
| RF05 | Controle TCP | START, STOP, intervalo, limites e status; requestId validado |
| RF06 | Telemetria UDP | Registro da origem, sequência, snapshot completo; testes com datagramas reais |
| RF07 | Alertas e recuperação | Latch global por métrica, histerese de 2; testes unitários e TCP |
| RF08 | Dashboard | Cards, barras, três gráficos de 60 leituras e histórico de 100 eventos implementados; inspeção visual pendente |
| RF09 | Estados de rede | TCP, registro UDP, espera, recepção, perda e parada distintos no cliente |
| RNF01 | Java 17 | Compilação `release 17`; execução local em JDK 24.0.2 |
| RNF02 | Portabilidade | Estratégias Windows/Linux/macOS; execução nativa demonstrada apenas em macOS |
| RNF03 | Responsividade | Rede fora da EDT; callbacks Swing por `invokeLater`; testes de transporte sem janelas |
| RNF04 | Limites de recursos | Pools definidos, fila de saída limitada, limites de mensagem e buffers históricos |
| RNF05 | Build reproduzível | Maven multi-módulo, versões fixadas, Surefire e Shade |
| RNF06 | Encerramento | Sockets fechados, tarefas interrompidas, testes de término dos executores |
| RNF07 | Transparência experimental | Simulação rotulada, indisponibilidade explícita, nenhuma captura ou resultado físico inventado |

Os requisitos de implementação não devem ser confundidos com a comprovação experimental em todas as plataformas e redes. As verificações pendentes estão identificadas ao final.

## 4. Arquitetura e módulos

```text
                    computador monitorado
        +--------------------------------------------+
        | netpulse-server                            |
        | Strategy -> MetricsSampler (500 ms)         |
        |                |                           |
        |      AtomicReference<SystemSnapshot>       |
        |                |                           |
        | avaliação de alertas + distribuidor UDP    |
        | sessões UUID: estado/intervalo/endpoint      |
        +-------------+----------------+-------------+
                      | TCP            | UDP
           comandos/RES/alertas       snapshots
                      |                |
        +-------------+----------------+-------------+
        | clientes A, B, C...                         |
        | netpulse-client: rede -> EDT -> Swing       |
        | A: 500 ms   B: 2000 ms   C: STOPPED          |
        +--------------------------------------------+

netpulse-server ---> netpulse-common <--- netpulse-client
                      modelos/protocolo
```

`common` contém tipos de domínio, disponibilidade, modos, métricas e codec/validação. `server` contém coleta, fábrica, estratégias, sessões e transporte. `client` contém transporte, controle de ordenação e interface. Cliente e servidor dependem somente do módulo comum, sem dependência entre si. Essa separação permite testar regras de mensagens sem inicializar hardware ou janelas.

A explicação detalhada da aplicação de POO, TCP, UDP e multithreading, com a localização das classes correspondentes, está em [Arquitetura e decisões técnicas](ARQUITETURA-E-DECISOES.md).

## 5. Escolha de Java 17

Java 17 oferece uma base estável para records imutáveis, sockets de fluxo e datagrama, executores e interface desktop. A JVM reduz a necessidade de versões distintas do código-fonte por sistema operacional. Essa portabilidade não elimina diferenças nativas de sensores, tratadas pela OSHI e representadas como disponibilidade de dados. O compilador é configurado com `release 17`, restringindo tanto formato de bytecode quanto APIs Java utilizadas. Executar em JDK mais recente não substitui uma verificação posterior no runtime 17 exato.

## 6. Swing e Java2D

Swing permite interface desktop sem servidor web, navegador embutido ou dependências de gráficos. Java2D é suficiente para traçar séries limitadas, eixos e escalas, evitando introduzir uma biblioteca externa para três gráficos simples. CPU e RAM usam escala 0–100%; temperatura utiliza 0–150°C, compatível com a faixa conservadora de plausibilidade do coletor.

Cada gráfico conserva até 60 leituras aceitas e quebra a linha em posições indisponíveis. A largura horizontal representa a ordem das leituras recebidas, não uma duração fixa: mudar o intervalo altera a janela temporal representada. A atividade local mantém até 100 entradas. O servidor mantém até 1000 alertas/recuperações persistentes, consultados em páginas de 50, e uma lista separada de alertas ativos. Esses limites contêm o crescimento de memória durante uso prolongado. O painel mantém valores anteriores se o fluxo parar, mas sinaliza ausência de telemetria para evitar interpretar valores antigos como atuais.

## 7. Maven e empacotamento

O parent Maven agrega três módulos e centraliza Java 17, codificação UTF-8 e JUnit. O Surefire executa testes automaticamente na construção. O Shade incorpora `common` e dependências transitivas nos executáveis e define `Main-Class`, permitindo execução por `java -jar` sem montagem manual de classpath. Recursos de serviços são combinados e assinaturas antigas de JAR são excluídas, pois o empacotamento modifica os artefatos originais.

O servidor foi inspecionado para confirmar a presença de OSHI, JNA, classes comuns e bibliotecas nativas de JNA. A versão de OSHI escolhida foi 6.9.3, disponível no Maven Central e baseada em JNA; não foi usado `oshi-core-ffm`. A API efetiva de `getSystemCpuLoadTicks()` e `getSystemCpuLoadBetweenTicks(long[])` também foi confirmada no JAR resolvido. As versões fixadas favorecem repetibilidade, embora o primeiro build ainda dependa da disponibilidade do repositório de artefatos.

## 8. OSHI e integração com o sistema operacional

OSHI expõe informações do sistema por uma API Java comum e delega detalhes nativos a implementações de plataforma. Isso permite obter CPU e memória sem exigir um aplicativo externo de monitoramento. O adapter `OshiMetricsReader.Hardware` concentra o acesso utilizado e permite substituir leituras reais por dados controlados nos testes. Testar a matemática dessa forma não comprova todos os drivers; complementa a verificação local real.

Falhas pontuais de uma métrica são isoladas. Uma falha de temperatura não invalida CPU/RAM e não muda automaticamente a origem para simulação. Valores inválidos, não finitos ou fora dos limites são rejeitados antes da construção do snapshot. Diagnósticos de indisponibilidade podem ser consultados pelo logging, sem produzir mensagens repetidas obrigatórias na interface.

## 9. Strategy Pattern

`MetricsStrategy` define `sample()` e `sourceDescription()`. Em modo real, a fábrica valida o SO e cria uma `OshiMetricsStrategy`; em modo simulado, escolhe `SimulatedMetricsStrategy` independentemente do SO. O OSHI seleciona as implementações nativas de CPU, memória e sensores conforme a plataforma. A identificação do SO na estratégia é usada para descrever diferenças de disponibilidade de temperatura, não para repetir a coleta em classes separadas. Um SO não suportado produz erro claro no modo real, e a simulação só ocorre por opção explícita.

O código real usa uma única estratégia, `OshiMetricsStrategy`, que delega as leituras ao `OshiMetricsReader`. Não há classes Windows/Linux/macOS vazias que repetem a mesma chamada. O padrão Strategy continua útil para separar dados reais dos dados simulados e para permitir outras fontes de métricas sem alterar sockets, sessões ou interface.

A estratégia simulada aplica passeio aleatório limitado: CPU varia no máximo 5 pontos, RAM 2 pontos e temperatura 1,5°C por amostra; os valores são limitados às respectivas faixas. A seed torna a sequência de valores reproduzível, embora timestamps continuem representando o relógio real. O modo aparece no handshake, em cada snapshot e na interface; não se mistura temperatura simulada com CPU real.

## 10. CPU e memória reais

CPU representa uso global do computador entre dois conjuntos de ticks, e não o consumo do processo Java. Na primeira leitura não existe intervalo anterior, portanto o resultado é indisponível. Nas leituras seguintes utiliza-se a API de carga entre ticks da OSHI e converte-se a fração para percentual. A amostragem de 500 ms evita leitura de hardware por cliente e permite aquecimento independente da conexão de usuários.

A memória é calculada por `100 × (total − disponível) / total`. Total não positivo, disponível negativo ou maior que total tornam a leitura indisponível. A interpretação de “disponível” segue o SO e a OSHI, podendo incluir memória recuperável de caches; o percentual não deve ser confundido automaticamente com a soma das memórias privadas dos processos.

## 11. Indisponibilidade de temperatura

A presença de sensor físico não implica acesso uniforme pela API. No Windows pode ser necessária integração opcional com LibreHardwareMonitor/jLibreHardwareMonitor, não presumida nesta implementação. Em Linux, interfaces hwmon/thermal podem não existir ou não estar acessíveis, especialmente em máquinas virtuais e contêineres. No macOS, hardware e permissões também influenciam a leitura.

Temperatura zero, negativa, não finita ou superior a 150°C é tratada como indisponível. Esse teto é uma política conservadora de plausibilidade, não uma especificação térmica universal. A interface apresenta “Indisponível”, o protocolo transmite `NA` e os alertas ignoram a métrica. Quando a leitura volta a ser válida, exibição e avaliação são retomadas. Essa política evita transformar ausência de sensor em uma falsa indicação de computador frio.

## 12. Snapshot global

O sampler publica um record imutável por `AtomicReference`. Todos os clientes observam a mesma fonte de leitura, com timestamp e modo consistentes. O custo de CPU/RAM/sensores depende da frequência de coleta, não do número de sessões. O servidor continua coletando mesmo sem clientes, simplificando o ciclo de vida e mantendo uma leitura recente disponível para novas conexões.

A transmissão é independente da coleta. Um cliente a 250 ms pode receber duas vezes os valores do mesmo snapshot; as sequências de envio são distintas. Um cliente a 2000 ms recebe somente a leitura mais recente a cada oportunidade, sem fila de snapshots antigos. O projeto prioriza atualidade sobre reprodução integral do histórico de coleta.

## 13. Justificativa de TCP

Comandos precisam de fluxo ordenado e detecção de interrupções. Uma alteração de limite deve chegar inteira, e sua confirmação precisa ser associada à requisição correspondente. TCP fornece um fluxo de bytes ordenado, com retransmissão, enquanto a aplicação acrescenta enquadramento por LF e requestId. Alertas e recuperações também usam esse fluxo porque representam transições que merecem comunicação confiável durante a conexão.

A sessão delega consultas de histórico e regras ao monitor de alertas, sem depender diretamente do componente de persistência. Codificação dos eventos e validação TCP ficam centralizadas nos tipos do módulo comum.

A confirmação `RES` informa o resultado do processamento na sessão. Não é garantia absoluta após falhas: se a aplicação cair entre executar e responder, o cliente pode não saber se o comando ocorreu. Por isso o cliente não anuncia sucesso antes de receber OK e encerra uma conexão cujo comando excedeu o timeout. Não se afirma que TCP seja sempre mais lento que UDP; os comportamentos observados dependem de rede, buffers e carga. O requisito é semântico, não um ranking universal de velocidade.

## 14. Justificativa de UDP

Cada pacote contém um snapshot completo e independente. Se uma leitura for perdida, a próxima normalmente fornece informação mais atual; esperar a retransmissão daquela amostra poderia atrasar a exibição sem recuperar valor equivalente para o operador. UDP permite enviar datagramas sem um mecanismo de retransmissão de transporte imposto ao fluxo de telemetria.

O preço dessa escolha é aceitar perda, duplicação e desordem. O cliente valida sequência e descarta pacotes antigos, sem retransmitir ou acumular uma fila de reordenação. Isso não garante menor latência, entrega ou ausência de congestionamento. A aplicação limita o intervalo mínimo e sinaliza períodos sem recepção. Os testes locais exercitam ordenação e funcionamento, mas não medem comportamento em rede congestionada nem quantificam perda por roteadores físicos.

## 15. Sockets e portas

TCP e UDP pertencem a protocolos distintos da camada de transporte. O número 5000 pode ser usado simultaneamente nos dois, pois sua identificação inclui o protocolo. O cliente mantém uma conexão TCP e um socket UDP; o socket UDP também envia REGISTER, permitindo que o servidor observe o endpoint de origem efetivo.

Não se presume que as portas efêmeras TCP e UDP do cliente sejam iguais. O registro periódico atualiza o destino e pode auxiliar a manutenção de NAT, mas não resolve todas as políticas de tradução, rotas ou filtragem. As regras de firewall precisam considerar TCP e UDP separadamente.

## 16. Concorrência do servidor

O acceptor aceita sockets e entrega o processamento a handlers. Um handler bloqueado esperando um comando não bloqueia novas conexões. Outro loop recebe registros UDP. O sampler possui scheduler próprio; o dispatcher possui outro, verificando sessões a cada 25 ms. O dispatcher usa um único socket UDP, sem criar threads ou sockets por cliente para telemetria.

As sessões ficam em `ConcurrentHashMap`, o snapshot em `AtomicReference` e a configuração mutável de cada sessão é protegida pelo monitor da própria sessão. A escrita TCP passa por uma fila limitada de 128 mensagens e um único writer por sessão. Essa serialização impede intercalação de bytes entre respostas e alertas e evita que uma escrita bloqueada paralise o sampler. A fila cheia fecha o cliente lento; o pool de writers e sua fila global também têm limites.

O limite de 16 conexões é imposto antes de submeter handlers, inclusive durante handshake. O encerramento fecha listeners e sockets de clientes, remove sessões, interrompe pools e aguarda sua terminação de forma limitada. A solução favorece simplicidade acadêmica e concorrência explícita; não pretende atender milhares de conexões com a eficiência de um event loop NIO.

## 17. Concorrência e interface do cliente

A EDT executa criação, atualização e pintura dos componentes. DNS, conexão, leituras e escritas ficam em executores de rede. Existe somente um leitor TCP contínuo, que separa respostas correlacionadas de eventos assíncronos; nenhuma ação de botão abre um segundo leitor do mesmo stream. O leitor UDP valida a origem configurada e os campos antes de notificar a interface.

A classe de transporte não depende de Swing. Callbacks são encaminhados pelo dashboard à EDT, e um identificador local de geração impede callbacks de uma conexão antiga de atualizar a nova. Comandos pendentes usam futures e timeout. Desconectar fecha recursos e falha futures pendentes, sem esperar leitura bloqueante na thread da interface. Essa arquitetura foi verificada em testes sem janelas. Na revisão da interface, o painel foi separado da janela e seu layout renderizado em memória para inspeção, sem controle da janela nativa. As atualizações de métricas destinadas à EDT conservam apenas o snapshot pendente mais recente; amostras intermediárias podem ser descartadas se a interface estiver ocupada. Isso limita a fila visual de telemetria, sem constituir benchmark de consumo. A avaliação visual interativa permanece separada.

## 18. Isolamento multicliente

Cada sessão possui START/STOP, intervalo, endpoint UDP, sequência e instante do último envio. Alterar a cadência ou parar A não altera a telemetria de B. Os limites e o estado dos alertas pertencem ao servidor: alterações feitas por A são compartilhadas com B e os eventos são enviados a todos os clientes conectados. Todos monitoram o mesmo computador, mas podem receber a fonte global em cadências diferentes. Não há múltiplas instâncias de OSHI por usuário.

O teste com três clientes reais de socket confirma registro, início, configuração de intervalos distintos, parada isolada e continuidade dos demais. Outro teste estabelece cinco conexões simultâneas. A desconexão remove somente a sessão encerrada; a coleta e o acceptor continuam disponíveis.

## 19. Protocolo de aplicação

A versão inicia com HELLO e WELCOME, associando um UUID à conexão. Comandos incluem requestId; respostas repetem esse identificador. GET_STATUS fornece estado, intervalo, limites e presença de endpoint. O protocolo especifica tamanhos máximos, campos exatos, faixas numéricas e ausência explícita por `NA`.

A telemetria contém UUID, sequência, timestamp, modo e três valores. Sequência permite descartar duplicatas/desordem, enquanto timestamp identifica o instante de coleta. A detecção de ausência usa relógio monotônico local, evitando depender da sincronização de relógios entre máquinas. Mensagens são texto UTF-8 estrito, sem desserialização Java ou campos livres capazes de introduzir delimitadores. A gramática completa e seus códigos de erro estão em [PROTOCOLO.md](PROTOCOLO.md).

## 20. Alertas e histerese

Um valor que atinge ou supera o limite ativa o estado de alerta; novas amostras altas não repetem o evento. A recuperação ocorre 2 pontos percentuais ou 2°C abaixo do limite. Essa faixa reduz alternância frequente quando a leitura oscila próxima ao valor configurado. Mudar o threshold reavalia o snapshot atual, sem aguardar a próxima transmissão UDP.

Indisponibilidade preserva o estado anterior, sem presumir normalidade. STOP suspende somente a transmissão UDP da sessão. A avaliação global continua, inclusive sem clientes, e todos os clientes conectados recebem eventos TCP. Limites muito baixos, como CPU=0, podem manter um alerta que não recupera sem ajuste, pois a recuperação exigiria valor negativo; isso decorre diretamente da regra de histerese e das faixas admitidas.

### Histórico compartilhado e persistência

As regras são globais do servidor. `AlertMonitor` avalia um único estado por métrica e publica transições para as filas TCP de todos os clientes, inclusive aqueles com telemetria parada. Uma conexão posterior pode consultar `GET_ACTIVE_ALERTS` e `GET_ALERTS`; não é necessário presenciar o disparo para conhecer o estado. O cliente apresenta abas para ativos, histórico compartilhado e atividade local. O painel principal delega carregamento, paginação e estado de atualização das tabelas a um componente de alertas separado.

`AlertHistory` conserva até 1000 eventos e os limites em arquivo textual, sem banco de dados. IDs globais permitem paginação estável por cursor e retomam a sequência após reinicialização. Cada evento preserva o modo real/simulado, timestamp e UUID da execução do servidor. O valor mostrado na lista de ativos é o registrado no disparo; a configuração atual é obtida por GET_STATUS. Na reinicialização, regras são restauradas e ativos são recalculados pelas novas leituras.

Um executor de persistência acrescenta eventos ao arquivo em intervalos de até 500 ms. Mudanças nas regras e compactações substituem uma versão temporária; a compactação é feita quando o journal passa de aproximadamente 1250 entradas, preservando as 1000 mais recentes. A escrita não bloqueia a coleta dentro do lock de sessão. O fechamento normal tenta salvar o restante, mas falhas abruptas e buffers do SO impedem prometer durabilidade transacional. Erros são registrados e retentados; arquivos corrompidos não são sobrescritos silenciosamente. Um lock evita escritores concorrentes no mesmo caminho. Qualquer cliente conectado pode alterar os limites; a última alteração processada prevalece, limitação adequada apenas ao contexto confiável de laboratório.

Os testes adicionais verificaram persistência de regras/eventos após reabertura, retenção, paginação, rejeição de arquivo inválido, consulta concorrente com notificações TCP e compartilhamento de alertas entre clientes. Não foi realizado benchmark de disco ou teste de interrupção de energia.

## 21. Segurança e limitações

O sistema foi projetado para rede acadêmica confiável. Não fornece TLS, autenticação de usuários, autenticação forte do servidor ou integridade criptográfica. UUID aleatório dificulta adivinhação casual, mas pode ser observado no tráfego e usado para redirecionar o endpoint de uma sessão. Validar mensagens não equivale a protegê-las contra um atacante ativo.

Os limites de tamanho, filas e conexões reduzem consumo acidental e algumas formas de abuso, mas não constituem defesa completa contra negação de serviço. O projeto não deve ser exposto à Internet pública sem controles adicionais. Firewalls e NAT podem impedir o retorno UDP; a aplicação sinaliza o problema, sem alterar roteadores automaticamente.

Há outras limitações: disponibilidade de temperatura, interpretação de RAM específica do SO, precisão do agendamento dependente da JVM/SO, possibilidade de bloqueio demorado dentro de APIs nativas e detecção não imediata de uma falha TCP silenciosa. A UI não recupera sessões automaticamente após queda; reconectar cria uma sessão nova, inicialmente parada. A compatibilidade de código planejada não substitui os testes em Windows/Linux e JDK 17.

## 22. Metodologia de testes

A verificação combina unidades determinísticas e integração em loopback. O hardware fake permite controlar ticks/carga, memória e falhas de sensor. O simulador usa seed. Os testes de protocolo exercitam limites, UTF-8, campos extras, `NA` e valores inválidos. A lógica de alertas e o tracker UDP são exercitados sem dependências gráficas.

A integração abre portas dinâmicas, usa sockets reais e espera limitada por timeouts/latches. Ela não depende de Wireshark, serviços externos, roteadores ou sensores reais. Os testes do cliente usam um peer de teste independente, preservando a regra de que `client` não depende de `server`. Uma verificação adicional executa o JAR real para conferir empacotamento e coleta nativa, fora da suíte determinística.

## 23. Resultados efetivamente executados

Ambiente: **macOS 26.3 (25D125), arm64; OpenJDK 24.0.2; Maven 3.9.9; compilação `release 17`**. Não havia Maven no PATH; a distribuição oficial foi extraída temporariamente em `/tmp`. O acesso inicial ao Maven Central foi necessário. Tentativas dentro da restrição de execução falharam por DNS e por permissão de bind de sockets; após execução autorizada com rede local disponível, o build concluiu. Esses bloqueios de ambiente não foram contabilizados como sucesso.

Comando final de build:

```sh
/tmp/apache-maven-3.9.9/bin/mvn -o -Dmaven.repo.local=/tmp/netpulse-m2 clean verify
```

O modo offline foi usado depois da resolução das dependências. A reprodução usual é `mvn clean verify`.

| Classe | Testes | Falhas/erros/ignorados |
|---|---:|---|
| ProtocolTest | 6 | 0 / 0 / 0 |
| MetricsTest | 4 | 0 / 0 / 0 |
| AlertStateTest | 2 | 0 / 0 / 0 |
| ServerIntegrationTest | 4 | 0 / 0 / 0 |
| TelemetryTrackerTest | 2 | 0 / 0 / 0 |
| ConnectionTest | 3 | 0 / 0 / 0 |
| AlertHistoryTest | 4 | 0 / 0 / 0 |
| **Total** | **25** | **0 / 0 / 0** |

O Maven registrou **BUILD SUCCESS** nos quatro projetos do reactor. O teste de encerramento verifica término dos executores do servidor e do cliente. Evidência textual: [build local](evidencias/build-local.log).

**Nota de atualização:** o log acima foi gerado antes da reorganização dos pacotes e da consolidação das classes reais por plataforma em `OshiMetricsStrategy`; por isso, os nomes completos das classes de teste no log mostram a estrutura anterior. O Maven não estava disponível no ambiente desta atualização, então o build não foi repetido após essas mudanças. Regenere essa evidência com `scripts/build.sh` antes de tratar o log como validação do código atual.

A execução do JAR do servidor com `--mode=real --bind=127.0.0.1` produziu handshake `REAL|macOS`, registro UDP e confirmação de START. Foram observados quatro snapshots: a primeira CPU veio `NA`, seguida por 9,31%, 9,65% e 12,68%; RAM permaneceu disponível; temperatura veio `NA` nas quatro leituras. Esses números são uma observação breve daquele computador, não benchmark ou garantia de desempenho. O teste não é uma captura de pacotes Wireshark. Evidência: [saída do teste real](evidencias/execucao-real.log).

Foi confirmada a presença no JAR de `oshi/SystemInfo.class`, `com/sun/jna/Native.class`, `SystemSnapshot` e 28 entradas de bibliotecas nativas JNA. O executável do cliente respondeu a `--help`. A janela Swing foi iniciada sem erro no terminal; a ferramenta de inspeção listou `ClientMain`, mas recusou o acesso à aplicação Java. Portanto, **não se afirma validação visual completa de layout, botões e gráficos**. Os testes de transporte do cliente passaram sem abrir janelas.

No JDK 24 apareceram avisos sobre acesso nativo da JNA e APIs usadas por ferramentas de build; eles não impediram o resultado observado. Não foi executado runtime Java 17 nesta máquina, nem OSHI nativo em Windows/Linux. Não há resultados medidos de perda, jitter, latência ou throughput através dos roteadores físicos.

**Campos reservados para complementação:**

- [Inserir evidência de execução com JDK 17 e identificação do ambiente.]
- [Inserir verificação visual da interface real: conectar, iniciar, parar, limites, gráficos e desconexão.]
- [Inserir resultados reais de execução Windows e Linux, se realizados.]
- [Inserir resultados e observações da avaliação do professor.]

## 24. Topologia física prevista — etapa posterior

Está planejada a validação no caminho abaixo, ainda **não executada**:

```text
PC1  --->  R1  --->  R2  --->  R3  --->  PC2
         192.168.0.0/16   172.16.0.0/12   10.0.0.0/8
            faixas informadas no enunciado
```

As faixas correspondem a R1, R2 e R3, respectivamente. Não constituem uma configuração final das interfaces, dos enlaces ou das rotas. Antes do experimento devem ser definidos endereços efetivos, máscaras dos enlaces, rotas de ida/volta, regras de firewall e eventual NAT. O servidor e o cliente deverão usar os endereços alcançáveis dessa topologia, e o registro UDP precisará ser observado no percurso efetivo.

**Reservado:** [tabela de endereços finais e interfaces].  
**Reservado:** [configuração de roteadores realmente aplicada e validada].  
**Reservado:** [imagem da topologia produzida posteriormente no Packet Tracer].  
**Reservado:** [data, equipamentos, responsáveis e resultados dos testes físicos].

Nenhum projeto Packet Tracer ou arquivo `.pkt` foi produzido nesta etapa. Resultados de loopback não comprovam encaminhamento entre essas redes.

## 25. Evidências de tráfego a inserir posteriormente

**TCP:** [inserir captura real de conexão, comandos, confirmações e alertas, com contexto da coleta].  
**UDP:** [inserir captura real de registro e snapshots, com análise fundamentada nos pacotes efetivamente observados].

Não foram produzidos documento separado de Wireshark, captura fictícia, screenshot de roteador ou conclusão experimental sobre tráfego ainda não coletado.

## 26. Conclusão

O NetPulse implementa coleta compartilhada, estratégias de origem substituíveis, sessões independentes, controle TCP e telemetria UDP, acompanhados por cliente Swing e testes locais. A separação entre amostragem e transmissão reduz a multiplicação de consultas ao hardware, enquanto o isolamento por sessão permite cadências diferentes; os limites de alerta são globais. A representação explícita de indisponibilidade evita resultados falsos quando sensores não são acessíveis.

A escolha por datagramas aceita perda de amostras em troca de priorizar o estado recente; o controle por TCP fornece ordenação e correlação, mas não elimina incertezas após falhas de processo ou conexão. Os resultados locais demonstram os comportamentos exercitados, sem comprovar operação através da topologia planejada. Os próximos passos são a inspeção visual, a execução em outros ambientes, o teste físico e a inserção de evidências autênticas de tráfego e configuração.

## 27. Referências

Fontes oficiais para consulta e reprodução; acesso durante a elaboração em 13/11/2026. Não foram atribuídos DOIs ou resultados experimentais a essas referências.

1. Oracle. [Java SE 17 — Socket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html). API de sockets e timeouts.
2. Oracle. [Java SE 17 — DatagramSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html). Transporte por datagramas.
3. Oracle. [The Event Dispatch Thread](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html). Regras de concorrência Swing; tutorial originalmente dirigido ao JDK 8, aplicado aqui ao modelo da EDT.
4. Apache Maven. [Introduction](https://maven.apache.org/what-is-maven.html) e [Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/). Build e execução de testes.
5. Apache Maven. [Shade — Executable JAR](https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html). Empacotamento e classe principal.
6. OSHI. [Repositório oficial](https://github.com/oshi/oshi) e [documentação do projeto](https://www.oshi.ooo/). Abstração de informações nativas.
7. Maven Central. [POM do oshi-core 6.9.3](https://repo.maven.apache.org/maven2/com/github/oshi/oshi-core/6.9.3/oshi-core-6.9.3.pom). Artefato efetivamente resolvido nesta entrega; API também inspecionada no JAR.
8. RFC Editor. [RFC 9293 — Transmission Control Protocol](https://www.rfc-editor.org/info/rfc9293/), 2022.
9. RFC Editor. [RFC 768 — User Datagram Protocol](https://www.rfc-editor.org/info/rfc768/), 1980.
