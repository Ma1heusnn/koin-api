# kotlinAPI

API REST de controle de despesas pessoais, feita em **Kotlin + Ktor** como projeto de estudo de
backend. Autenticação por JWT com refresh token, persistência em MySQL via Exposed e migrations
versionadas com Flyway.

> ⚠️ **Projeto de estudo.** Roda local, não está em produção. Há achados de segurança conhecidos e
> ainda abertos, documentados em [`code-review/ACHADOS-ABERTOS.md`](code-review/ACHADOS-ABERTOS.md).
> Não use como está em ambiente exposto à internet.

## Stack

| Peça | O que faz |
|---|---|
| Ktor 3.4 (Netty) | Servidor HTTP e roteamento |
| Kotlin 2.3 / JDK 25 | Linguagem e toolchain |
| Exposed 0.59 | Acesso ao banco (SQL tipado em Kotlin) |
| Flyway 12 | Migrations versionadas do schema |
| HikariCP | Pool de conexões |
| MySQL 9 (H2 nos testes) | Banco |
| jBCrypt | Hash de senha |
| kotlinx.serialization | JSON |

## Endpoints

Tudo que está sob **🔒** exige o header `Authorization: Bearer <access token>`.

### Usuários e autenticação

| Método | Rota | O que faz |
|---|---|---|
| `POST` | `/users` | Cadastra um usuário |
| `POST` | `/users/login` | Login por e-mail **ou** username → access + refresh token |
| `PATCH` | `/users/profile` | 🔒 Edita o próprio perfil |
| `POST` | `/auth/refresh` | Troca o refresh token por um novo par (com rotação) |
| `POST` | `/auth/logout` | 🔒 Revoga o refresh token atual |
| `POST` | `/auth/logout-all` | 🔒 Revoga todos os refresh tokens do usuário |

### Categorias

| Método | Rota | O que faz |
|---|---|---|
| `GET` | `/categories` | 🔒 Lista as categorias globais + as do usuário |
| `POST` | `/categories` | 🔒 Cria uma categoria do usuário |
| `PATCH` | `/categories/{id}` | 🔒 Edita uma categoria própria |
| `DELETE` | `/categories/{id}` | 🔒 Apaga uma categoria própria |

### Custos

| Método | Rota | O que faz |
|---|---|---|
| `GET` | `/costs` | 🔒 Lista os custos do usuário |
| `POST` | `/costs` | 🔒 Registra um custo |
| `GET` | `/costs/{id}` | 🔒 Busca um custo próprio |
| `PATCH` | `/costs/{id}` | 🔒 Edita um custo próprio |
| `DELETE` | `/costs/{id}` | 🔒 Apaga um custo próprio |

## Como rodar

**1. Variáveis de ambiente.** Copie o template e preencha:

```bash
cp .env.example .env
```

O arquivo `.env` está no `.gitignore` e nunca vai para o Git. Atenção: **a JVM não lê esse arquivo
sozinha** — use *IntelliJ → Run → Edit Configurations → Environment variables*, ou exporte as
variáveis no shell antes de subir o servidor.

| Variável | Para quê |
|---|---|
| `JWT_SECRET` | Chave que assina os JWT. Gere com `openssl rand -base64 32` |
| `DB_URL` | Ex.: `jdbc:mysql://localhost:3306/koin` |
| `DB_USER` / `DB_PASSWORD` | Credenciais do MySQL |

Sem qualquer uma delas o app **não sobe** — é de propósito: segredo não tem valor padrão.

**2. Banco.** Crie o database vazio (ex.: `CREATE DATABASE koin;`). O schema é criado pelo Flyway no
boot, a partir de `src/main/resources/db/migration`.

**3. Suba o servidor.**

```bash
./gradlew run
```

Se subir, você vê:

```
[main] INFO Application - Application started in 0.303 seconds.
[main] INFO Application - Responding at http://0.0.0.0:8080
```

## Tarefas do Gradle

| Tarefa | O que faz |
|---|---|
| `./gradlew run` | Sobe o servidor |
| `./gradlew test` | Roda os testes (H2 em memória — não toca no MySQL) |
| `./gradlew build` | Compila tudo |
| `./gradlew buildFatJar` | Gera o JAR executável com as dependências |

## Estrutura

```
src/main/kotlin/com/koin/
├── Application.kt      boot e instalação dos plugins
├── Routing.kt          registro das rotas
├── factory/            pool Hikari, Flyway e dbQuery
├── models/             entidades, DTOs e validações
├── routes/             camada HTTP
├── services/           regra de negócio
├── tables/             tabelas Exposed
├── security/           JWT e helpers de autenticação
├── plugins/            StatusPages (tratamento de erro)
└── serializers/        BigDecimal em JSON
```

O fluxo é sempre `rota → service → tabela`.

## Code review

A pasta [`code-review/`](code-review/) guarda o histórico de revisão do projeto: o que já foi
corrigido ([`PROGRESSO.md`](code-review/PROGRESSO.md)) e o que continua em aberto
([`ACHADOS-ABERTOS.md`](code-review/ACHADOS-ABERTOS.md)). É material de estudo — cada achado explica
o bug, o impacto e a regra que ele ensina.
