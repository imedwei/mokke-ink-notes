# Claude Code Review Setup

This repository uses [claude-code-action](https://github.com/anthropics/claude-code-action) to provide automated code review on pull requests and interactive assistance via `@claude` mentions.

## Prerequisites

- A Claude Max (or Pro/Team) subscription with Claude Code CLI installed
- Admin access to the GitHub repository
- The [Claude GitHub App](https://github.com/apps/claude) installed on the repository

## Step 1: Install the Claude GitHub App

1. Visit https://github.com/apps/claude
2. Click **Install** and select the repository
3. Grant the requested permissions (contents, issues, pull requests)

## Step 2: Obtain your OAuth token

The GitHub Action authenticates using your Claude Code OAuth token from your local machine.

### macOS

1. Make sure you're logged into Claude Code locally:

   ```bash
   claude auth status
   ```

   If not logged in, run:

   ```bash
   claude login
   ```

2. Extract the OAuth access token from the macOS Keychain:

   ```bash
   security find-generic-password -s "Claude Code-credentials" -w
   ```

3. The output is a JSON object. Copy the `accessToken` value from the `claudeAiOauth` field:

   ```json
   {"claudeAiOauth":{"accessToken":"sk-ant-oat01-...","refreshToken":"sk-ant-ort01-..."}}
   ```

   The value you need is the `accessToken` string (starts with `sk-ant-oat01-`).

### Linux

```bash
cat ~/.claude/credentials.json
```

Extract the `accessToken` value from the `claudeAiOauth` field.

### Windows

The credentials are stored in Windows Credential Manager under `Claude Code-credentials`. You can retrieve them using:

```powershell
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String((cmdkey /list:Claude` Code-credentials | Select-String -Pattern "Generic")))
```

Or use Credential Manager UI: Control Panel > User Accounts > Credential Manager > Windows Credentials > `Claude Code-credentials`.

## Step 3: Add the token as a GitHub secret

1. Go to your repository on GitHub
2. Navigate to **Settings > Secrets and variables > Actions**
3. Click **New repository secret**
4. Name: `CLAUDE_CODE_OAUTH_TOKEN`
5. Value: paste the `accessToken` value from Step 2
6. Click **Add secret**

## Step 4: Verify the workflow file

The workflow file at `.github/workflows/claude.yml` should already be in the repository. It configures:

- **Auto-review**: Automatically reviews PRs when opened or updated
- **Interactive mode**: Responds to `@claude` mentions in PR comments, review comments, and issues

## Usage

### Automatic PR review

Every time a PR is opened or updated, Claude will automatically review it for:
- Code quality and best practices
- Potential bugs or issues
- Performance considerations
- Security concerns
- Test coverage

### Interactive assistance

Mention `@claude` in any PR comment, review comment, or issue to ask Claude for help. Examples:

- `@claude explain this change`
- `@claude suggest a better approach for this function`
- `@claude fix the failing test`

## Token expiration

The OAuth token may expire periodically. If the action starts failing with authentication errors, repeat Step 2 to get a fresh token and update the GitHub secret.

## Alternative: API key authentication

If you prefer to use an Anthropic API key instead of OAuth (requires API credits):

1. Get an API key from [console.anthropic.com](https://console.anthropic.com)
2. Add it as a secret named `ANTHROPIC_API_KEY`
3. Change the workflow to use `anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}` instead of `claude_code_oauth_token`
