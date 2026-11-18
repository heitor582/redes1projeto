# NetPulse

Monitoramento remoto de **CPU da máquina inteira, RAM física e temperatura quando disponível**, com servidor e cliente Swing. Java 17, TCP para controle e alertas, UDP para snapshots. Até 16 sessões simultâneas, com intervalo e START/STOP independentes. Os limites e alertas são globais no servidor.

## Requisitos e build

- JDK **17 ou superior**, com ambiente gráfico no computador que executa o cliente.
- Maven 3.9+ no PATH; o primeiro build precisa acessar o Maven Central.
- Plataformas principais de execução: **Windows e Linux**. macOS é suporte adicional, sem validação equivalente nesta entrega.
- CPU e RAM usam OSHI 6.9.3 (`oshi-core`, baseado em JNA); não é necessário instalar outro aplicativo de monitoramento.

Um comando constrói e verifica os três módulos (`common`, `server` e `client`). Execute pelo Bash na raiz do projeto:

```sh
bash scripts/build.sh
```

No Linux, o mesmo comando funciona em Bash. No Windows, abra o **Git Bash** ou o **WSL**: no Git Bash, Java e Maven do Windows precisam estar no `PATH`; no WSL, instale Java e Maven dentro do ambiente Linux. O macOS pode usar o mesmo script como suporte adicional.

Para encerrar os processos do servidor e dos clientes iniciados pelos JARs do projeto, use:

```sh
bash scripts/stop.sh
```

No Linux/macOS, o script envia SIGTERM aos processos NetPulse iniciados por JAR, permitindo o fechamento normal do servidor e a gravação pendente do histórico. No Windows, execute pelo Git Bash; o script usa PowerShell e tenta primeiro o encerramento normal por `jcmd`. Se precisar encerrar à força, pode perder alterações do histórico ainda não gravadas. O script localiza JARs `netpulse-server` e `netpulse-client`; processos iniciados pela IDE devem ser encerrados pela IDE. No WSL, ele encerra apenas processos Java executados dentro do próprio WSL.

O build produz dois JARs executáveis com dependências:

```text
netpulse-server/target/netpulse-server-1.0.0.jar
netpulse-client/target/netpulse-client-1.0.0.jar
```

Use esses nomes, não os arquivos `original-*.jar` gerados pelo Shade. O servidor inclui OSHI, JNA, JNA Platform e bibliotecas nativas transitivas. O cliente inclui `common` e usa Swing/Java2D do JDK.

## Executar

Servidor com métricas reais:

```sh
java -jar netpulse-server/target/netpulse-server-1.0.0.jar --mode=real --tcp-port=5000 --udp-port=5000
```

Demonstração simulada, com seed reprodutível (42 por padrão):

```sh
java -jar netpulse-server/target/netpulse-server-1.0.0.jar --mode=simulated --seed=42 --tcp-port=5000 --udp-port=5000
```

Cliente, em outro terminal:

```sh
java -jar netpulse-client/target/netpulse-client-1.0.0.jar
```

Para iniciar manualmente, use um terminal na raiz do projeto. No Windows, os mesmos comandos `java -jar` funcionam no PowerShell ou Prompt de Comando; no Linux, use um terminal bash. Envolva o caminho do JAR em aspas se ele tiver espaços. `--help` exibe ajuda em ambos os executáveis.

1. Informe `localhost` no teste local, ou o IP/hostname alcançável do servidor no teste remoto.
2. Informe as portas TCP e UDP e clique **Conectar**. A sessão começa parada.
3. Aguarde **UDP: Registrado** e clique **Iniciar**. CPU pode estar indisponível durante o aquecimento inicial.
4. Modifique intervalo (250–5000 ms) e limites; os comandos só aparecem como confirmados após resposta TCP.
5. Para demonstrar alertas em simulação, aplique CPU=30, RAM=30 ou TEMP=30. A simulação começa próxima de CPU=45%, RAM=55%, TEMP=50°C.
6. Execute o comando do cliente em outros terminais para abrir várias instâncias. Parar um painel não interrompe os demais.

`--bind=0.0.0.0` é o padrão do servidor, escutando as interfaces IPv4. Para limitar a execução local, use `--bind=127.0.0.1`. É possível especificar outro endereço de interface. O limite de 16 sessões protege os pools; conexões adicionais são fechadas.

## Interpretar os dados e os estados

- CPU é a utilização global entre leituras de ticks, e não o consumo isolado da JVM.
- RAM corresponde a `100 × (total − disponível) / total`, conforme a interpretação de memória disponível do SO/OSHI.
- Temperatura é **best-effort**. Hardware, permissões e drivers podem impedir a leitura. Windows pode depender de integração opcional com LibreHardwareMonitor; Linux depende de sensores expostos; macOS varia por hardware. NetPulse não exige essas integrações e mostra **Indisponível** quando não há leitura válida.
- Não há substituição automática por simulação. O rótulo **DADOS REAIS** ou **SIMULAÇÃO** identifica a origem em toda sessão.
- TCP conectado, UDP registrado, aguardando/recebendo telemetria, sem telemetria e parado são estados separados. Registro UDP é uma confirmação da aplicação, não uma conexão confiável do transporte.
- Sem telemetria significa ausência de pacote válido por mais de `max(4000 ms, 3 × intervalo)`, usando relógio local monotônico. Os últimos valores podem continuar visíveis; consulte o estado antes de interpretá-los como atuais.
- Alertas disparam ao atingir/superar o limite e recuperam 2 unidades abaixo dele. Indisponibilidade não gera alerta nem recuperação falsa. STOP pausa somente a telemetria UDP daquela sessão; alertas e recuperações globais chegam por TCP a todos os clientes conectados.

## Rede e segurança

Libere no firewall do servidor **TCP 5000 e UDP 5000 como regras distintas**, ou as portas escolhidas. O cliente registra o endpoint UDP pelo mesmo socket usado para receber e renova o registro a cada 10 segundos. Não é necessário presumir que a porta TCP do cliente seja igual à UDP.

NAT, rotas e firewalls ainda podem impedir o retorno; renovação não garante travessia de qualquer NAT. Use uma rede de laboratório confiável. O protocolo não tem TLS, autenticação forte ou integridade criptográfica; UUID é um identificador imprevisível, não proteção suficiente para Internet pública. Não publique essas portas na Internet como se fosse um produto seguro.

## Organização

```text
pom.xml
netpulse-common/   modelos imutáveis, validação e protocolo
netpulse-server/   metrics/, alerts/ e network/
netpulse-client/   network/ e ui/
docs/PROTOCOLO.md  gramática, estados, erros e limites
docs/RELATORIO.md  relatório acadêmico e evidências pendentes
docs/ARQUITETURA-E-DECISOES.md  POO, TCP, UDP e multithreading no código
scripts/          build e encerramento por Bash (Linux, Git Bash/WSL e macOS adicional)
```

A coleta global ocorre a cada 500 ms; a distribuição verifica sessões a cada 25 ms. Intervalos abaixo de 500 ms podem transmitir o mesmo snapshot com sequências novas. Não são prometidos precisão de tempo real, ausência de perda ou latência garantida.

## Testes e validação desta entrega

`mvn clean verify` executa **25 testes JUnit 5**, incluindo sockets reais em localhost com portas dinâmicas. Não abre janelas Swing nem usa rede externa para executar os testes; a resolução inicial das dependências exige Internet. Resultados detalhados ficam em `netpulse-*/target/surefire-reports/`.

O ambiente desta entrega não tinha `mvn` no PATH: foi usado Maven 3.9.9 extraído em `/tmp`, com repositório local temporário. Isso não é uma dependência do projeto; em sua máquina use Maven instalado normalmente. O build usa `--release 17`; as versões efetivamente executadas e a verificação real do JAR estão registradas no relatório.

**Testes locais não comprovam funcionamento através de roteadores físicos.** A execução nativa desta entrega foi feita em macOS; o código está direcionado a Windows e Linux, cuja execução precisa ser confirmada nos computadores do laboratório. A topologia PC1 → R1 → R2 → R3 → PC2, as capturas Wireshark e a avaliação do professor permanecem etapas separadas. Nenhum `.pkt`, captura fictícia ou PDF foi produzido.

## Interface e uso em computadores distintos

O painel usa tema escuro, controles à esquerda e cards de CPU, RAM e temperatura. Antes de receber dados, mostra “Aguardando leitura do servidor”; indisponibilidade só é indicada após uma leitura válida com métrica ausente. A temperatura pode faltar mesmo quando CPU e RAM estão disponíveis.

Para executar em máquinas diferentes, inicie o servidor **sem** `--bind=127.0.0.1` (o padrão é `0.0.0.0`) e informe no cliente o IP/hostname do computador servidor. `localhost` sempre aponta para a própria máquina do cliente. Libere ambas as portas no firewall e verifique o caminho de retorno UDP.

Para pré-preencher uma configuração sem mudar os defaults do projeto:

```sh
java -Dnetpulse.host=192.168.1.10 -Dnetpulse.tcpPort=5050 -Dnetpulse.udpPort=5050 -jar netpulse-client/target/netpulse-client-1.0.0.jar
```

O endereço é apenas exemplo; substitua pelo IP real. Servidor e cliente precisam usar as mesmas portas configuradas.

A coleta compartilhada, os pools limitados e os buffers limitados controlam o uso de recursos. Atualizações de métricas para a EDT são agrupadas: se ela estiver ocupada, somente a amostra mais recente pendente será exibida. Isso evita uma fila crescente de snapshots, mas não substitui benchmark; nenhum teste de carga ou medição formal de consumo foi realizado.

## Alertas globais e histórico persistente

Se um cliente configurar RAM em 70%, essa regra passa a valer para o servidor e para todos os clientes. Ao atingir o limite, todos os clientes conectados recebem um alerta TCP; a recuperação ocorre em 68% ou menos. Não há cópia do mesmo evento por cliente no histórico. As notificações funcionam mesmo se a telemetria daquele cliente estiver parada.

- **Alertas ativos:** regras disparadas que ainda não recuperaram. Os valores apresentados são os registrados no disparo.
- **Histórico do servidor:** alertas e recuperações mais recentes, em páginas de 50. Use **Mais antigos** para carregar outras páginas.
- **Atividade local:** confirmações e mensagens da conexão atual.

As listas são consultadas ao conectar, após eventos/configurações e pelo botão **Atualizar**. Quem conecta depois de um disparo consegue consultar o alerta já ativo, sem depender de ter recebido a notificação original.

O servidor guarda até **1000 eventos** e os três limites em `data/alerts.log`, relativo ao diretório de execução. Para outro caminho, use `--history-file=/caminho/alerts.log`. Não há banco de dados. Um arquivo auxiliar `.lock` impede duas instâncias de gravarem o mesmo histórico. O diretório `data/` não faz parte do código versionado.

A gravação ocorre em segundo plano: novos eventos são acrescentados ao arquivo, e mudanças nas regras reescrevem uma cópia temporária protegida. O journal é compactado ao atingir cerca de 1250 linhas, mantendo os 1000 eventos mais recentes. O encerramento normal tenta concluir a gravação. Um desligamento abrupto pode perder a última janela de alterações; erros de disco são registrados no terminal e tentados novamente. Um `OK` confirma a aplicação da regra em memória, não uma garantia de durabilidade contra queda de energia. Ao reiniciar, os limites são restaurados e os alertas ativos são reavaliados com leituras novas; o histórico anterior permanece dentro da retenção.

Qualquer cliente conectado pode alterar regras globais: a última alteração processada prevalece, e os demais recebem a atualização. Não há autenticação ou perfis de administrador nesta versão; use somente a rede de laboratório confiável. Atualize servidor e clientes juntos, pois os novos comandos e eventos ampliam o protocolo anterior.
