# ktor-backend-mentor — pacote para Claude Code

Duas peças que se complementam:

| Peça | Onde roda | Para quê |
|---|---|---|
| **Skill** (`skills/ktor-backend-mentor/`) | Na sua conversa principal do Claude Code; dispara sozinha quando o assunto é Kotlin/Ktor | Mentoria contínua: você conversa, ela ensina no fluxo |
| **Subagente** (`agents/ktor-backend-mentor.md`) | Em contexto isolado; recebe uma tarefa e devolve um relatório didático | Tarefas delegáveis: "revisa esse módulo", "implementa a auth", sem poluir seu contexto principal |

Instale as duas — o subagente inclusive pré-carrega a skill via campo `skills:` do frontmatter.

## Instalação

**Opção A — pessoal (vale para todos os seus projetos):**

```bash
# a partir da pasta deste pacote descompactado:
mkdir -p ~/.claude/skills ~/.claude/agents
cp -r skills/ktor-backend-mentor ~/.claude/skills/
cp agents/ktor-backend-mentor.md ~/.claude/agents/
```

**Opção B — por projeto (versionável no Git junto com o time):**

```bash
# a partir da raiz do seu projeto:
mkdir -p .claude/skills .claude/agents
cp -r <pacote>/skills/ktor-backend-mentor .claude/skills/
cp <pacote>/agents/ktor-backend-mentor.md .claude/agents/
```

No Windows (PowerShell), os destinos equivalentes são `%USERPROFILE%\.claude\skills\` e `%USERPROFILE%\.claude\agents\` (ou `.claude\` na raiz do projeto).

Observações:
- Se esta for a **primeira vez** que a pasta `agents/` ou `skills/` existe nesse escopo, reinicie a sessão do Claude Code para ela ser detectada. Depois disso, edições nos arquivos são captadas automaticamente.
- Se instalar **só o agente** (sem a skill), remova as linhas `skills:` e `  - ktor-backend-mentor` do frontmatter dele — o agente continua funcional, apenas sem as referências detalhadas.

## Como usar

- **Skill (automática):** basta conversar — "monta uma API de produtos em Ktor com Exposed", "por que meu endpoint tá dando 415?". Quando o assunto é backend Kotlin/Ktor, ela ativa e conduz no modo mentor.
- **Agente (delegação):** peça explicitamente — `Use o agente ktor-backend-mentor para revisar src/main/kotlin/...` — ou deixe o Claude delegar sozinho quando a tarefa bater com a descrição. O agente trabalha à parte e volta com um relatório estruturado (o que fez, por quê, armadilhas, o que você leva daqui).

## Dica de fluxo

Aprendizado e decisões de arquitetura → converse no thread principal (skill). Tarefas volumosas ou revisões de muitos arquivos → delegue ao agente, que faz a leitura pesada no contexto dele e te devolve só a aula.
