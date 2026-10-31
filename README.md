# Matilha 🐾 — app Android de socialização segura para cães

O app gira em torno do **cão**. O humano é o "motorista" e responsável legal, e uma conta pode ter vários pets.
O mapa, os filtros e os alertas usam o perfil comportamental de cada pet.

## O que o MVP faz

### 1. Perfil comportamental do pet
- Raça (com atalho para SRD), faixa etária (Filhote/Adulto/Sênior) e peso. O porte é **calculado** pelo peso:
  Mini ≤5 kg, Pequeno 5–10 kg, Médio 10–25 kg, Grande 25–45 kg, Gigante acima de 45 kg.
- Castrado ou inteiro, e se a fêmea está no cio (só aparece para fêmea inteira).
- Vacinas V8/V10, Raiva, Giárdia e Gripe, com data da última dose e validade de 12 meses.
- Controle de pulgas/carrapatos, com validade de 90 dias.
- **Selo "Vacinação em dia"**: exige as 4 vacinas válidas e a foto da carteirinha anexada.
  A verificação automática do MVP confere as datas informadas.
- Bússola de energia de 1 a 5.
- Estilos de brincadeira, que podem ser vários.
- Gatilhos: reativo na guia, possessivo com recursos e seletivo. O seletivo detalha o sexo
  ("só fêmeas") e os portes que dão medo.

### 2. Mapa vivo (tela inicial)
- Mapa OpenStreetMap: arrastar, pinçar, toque duplo, botões +/− e "minha localização".
- Pinos com **cor + número/símbolo + legenda** (nunca só cor):
  - cinza: vazio
  - verde: com cães
  - amarelo: perfis no local podem estressar **o seu** pet
  - vermelho: alerta ativo
- Filtros: **Fuga da Muvuca** (vazio agora e normalmente vazio nesta hora), **Sombra agora**, **Cercados**,
  **Rolês privados**.
- **Lista de locais** como alternativa acessível ao mapa (TalkBack).
- **Radiografia do local**:
  - cercamento (portão duplo, simples, semi-aberto, aberto → "exige guia")
  - piso, com alerta de lama em terra batida e de **piso quente das 10h às 16h** em cimento/asfalto
  - infraestrutura (iluminação, bebedouro, bancos na sombra, lixeiras, saquinhos)
  - O consenso vem dos votos de vários tutores. Em empate, vence a opção **menos segura**.
- **Alertas em tempo real (Waze Pet)**: grama com carrapatos, vidro, veneno e cão agressivo solto.
  Cada alerta pode ser confirmado ou marcado como resolvido e expira sozinho (o de cão agressivo em 3 h).
- **Modo passeio solo**: gráfico de silêncio/movimento por hora, faixas em que o local costuma ficar vazio
  e horários com sombra contínua.
- **Ao vivo**:
  - quantos cães estão no local agora
  - **resumo de harmonia** (ex.: "4 cães de porte pequeno/médio, energia moderada")
  - **alerta de conflito automático**, por exemplo medo de gigantes, machos inteiros, cio, lutador grande
    contra cão tímido ou pequeno, ou perfil em revisão
- **Agenda de 30 em 30 min (06h–23h)**: "Estarei lá das 17h30 às 18h30", com o botão **"Eu também vou!"**.
- **Eventos públicos temáticos** (encontro de raça, caminhada de idosos, socialização de filhotes).

### 3. Rolês privados ("Abre o Quintal")
- No mapa aparece só um **círculo aproximado**: o centro é deslocado 250–550 m do endereço real, que fica dentro do círculo.
- O **endereço exato e o chat** só aparecem depois que o anfitrião aceita o pedido.
- **Filtros de porta**: peso, porte, idade, energia, comprovante de vacina obrigatório, proibido macho inteiro
  e proibido fêmea no cio. Pets fora do filtro veem o motivo, e o botão **"Pedir para participar" fica desativado**.
- **Checklist do anfitrião**: piscina, gramado cercado, sombra, água, brinquedos, lanche/cerveja (racha via Pix).
- **Matilha** (lista de amigos), **melhor amigo ★** e **rolê invisível no mapa**, só para convidados da Matilha.

### 4. Notificações inteligentes
- **Melhor amigo**:
  - "O Paco (amigo da sua cachorra Lola) acabou de fazer check-in em Parcão…, a 400 m de você!"
  - "O tutor do Paco agendou presença … hoje às 18h00."
- **Radar de compatibilidade**: quantidade mínima, porte, castrados, estilo, raio e janela de horário.
  Dispara no máximo uma vez a cada 3 h.
- **Check-out automático**:
  - Uma cerca virtual do sistema (`LocationManager.addProximityAlert`, baixo consumo) usa o raio do local + 150 m.
  - Ao sair, o app confirma 5 min depois; se você ainda estiver fora, encerra o check-in e pergunta
    *"Como foi o passeio? Tudo limpo e seguro?"*.
  - Redes de segurança: atualizações de localização com o app aberto e encerramento automático após 4 h.
- **Score de confiança**:
  - Depois de um encontro de 10 min ou mais, ou de um rolê privado, o app faz a pergunta rápida
    *"O perfil do cão X condiz com a realidade?"*.
  - Com **2 tutores distintos** dizendo "não condiz" (e eles sendo maioria), o perfil entra **em revisão**.
  - Um perfil em revisão fica marcado para todos e é barrado nos rolês privados.

### Acessibilidade e públicos diversos
- Português do Brasil.
- Áreas de toque de pelo menos 48 dp.
- Textos em `sp`, que respeitam o tamanho de fonte do sistema (testado em 160%).
- Tema claro e escuro com cores de contraste AA.
- Avisos sempre com ícone e texto, nunca só cor.
- Chips anunciados como caixa de seleção pelo TalkBack, títulos marcados como cabeçalho e lista alternativa ao mapa.

## Limitações conhecidas (honestas)
- **Sem servidor ainda.** Os dados do usuário ficam no aparelho (arquivo com gravação atômica e backup).
  Os outros tutores e cães são uma **comunidade simulada** de Brasília (`data/CommunitySimulator.kt`):
  - a presença nas praças segue a ocupação típica de cada local
  - anfitriões simulados aprovam pedidos
  - pedidos de amizade são aceitos

  Toda a lógica passa pelo `Repository`, que é onde entraria um backend real.
- O comprovante de vacina é conferido pelas datas informadas. Não há análise da imagem.
- A localização usa o `LocationManager` do sistema, sem Google Play Services, para funcionar em qualquer aparelho.
  A cerca virtual em segundo plano depende da permissão "Permitir o tempo todo".

## Arquitetura
```
app/src/main/java/com/matilha/app/
  domain/    Models.kt, Rules.kt   ← Kotlin puro: compatibilidade, harmonia, filtros de porta, vacinas,
                                     consenso de locais, horários vazios, agenda, radar, confiança
  data/      AppState, Repository   ← estado imutável + todas as ações do usuário (validadas)
             Seed, CommunitySimulator, Fmt
  platform/  Platform.kt            ← Application, notificações, geofence, JobScheduler, boot, localização
  ui/        Ui.kt (design system), MapView.kt (OSM), screens/*
  MainActivity.kt                   ← pilha de navegação, abas, permissões
```

## Verificações automáticas
- **72 testes**: regras de domínio (19), repositório/fluxos (21), plataforma — notificações, cerca virtual,
  check-out automático, job e boot (6) — e 13 jornadas de UI executadas **duas vezes**: Android 14 e Android 8.0 (minSdk).
- Auditoria de acessibilidade em 15 telas (nome acessível, alvo ≥ 44 dp, texto ≥ 12 sp), modo escuro e fonte 160%.
- O `build-apk.sh` falha se:
  - algum `invokedynamic` alcançável chegar ao DEX (o ART não tem `LambdaMetafactory` e o `dx` não faz desugaring) — `tools-check-indy.py`;
  - algum componente do manifest faltar no DEX — `tools-check-dex.py`.
- Compatibilidade com API 26: `gradle :app:compileKotlin -PcompileApi=26` lista todo uso de API mais nova;
  cada um está protegido por `Build.VERSION.SDK_INT` ou é uma constante/setter antigo.

## Como compilar e testar
O build não usa o Android Gradle Plugin, porque o ambiente original não acessava o Google Maven.
O script `build-apk.sh` monta o APK com `aapt`, `dx`, `zipalign` e `apksigner` (pacotes do Ubuntu):

```bash
./build-apk.sh          # gera dist/matilha.apk (assinado com chave de debug)
./fetch-robolectric.sh                            # runtime Android 14 do Robolectric
./fetch-robolectric.sh 8.0.0_r4-robolectric-r1-i7 # runtime Android 8.0
gradle :app:test        # testes unitários + testes de UI (Robolectric) + capturas em screens/
```

Requisitos:
- JDK 17 ou mais recente, Gradle 8.x
- `/opt/android-sdk/platforms/android-35/android.jar` (compilação)
- `/opt/android-sdk/platforms/android-33/android.jar` (ligação de recursos)
- pacotes `aapt dalvik-exchange zipalign apksigner`
