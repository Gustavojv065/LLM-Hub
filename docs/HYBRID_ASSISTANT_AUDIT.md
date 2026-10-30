# Auditoria técnica — LLM Hub Assistente Híbrido (Fase 1)

Escopo: fork `Gustavojv065/LLM-Hub`, Android (Kotlin + Jetpack Compose). Inspeção estática da branch `main`, sem modificar produção, Supabase, nem os serviços de inferência do projeto.

## Evidências existentes

| Recurso | Evidência no código | Decisão |
| --- | --- | --- |
| Gemma 4 local | `data/ModelData.kt`, `inference/UnifiedInferenceService.kt` | Preservado. O modelo precisa ser baixado/carregado pelo usuário. |
| Conversa local, RAG, sessões | `viewmodels/ChatViewModel.kt`, `screens/ChatScreen.kt` | Preservados; sem mexer na tela do chat principal. |
| Whisper ASR (português via variante multilingual) | `inference/WhisperKitService.kt`, `data/ModelData.kt` | Preservado; a integração existente usa o app, não a API online. |
| Kokoro TTS | `data/ModelData.kt`, `screens/SettingsScreen.kt` | Preservado; verificar vozes disponíveis e pronúncia PT-BR no aparelho. |
| Stable Diffusion | `imagegen/StableDiffusionHelper.kt`, `screens/ImageGeneratorScreen.kt` | Preservado; compatibilidade de backend e RAM depende do dispositivo. |
| Agentes, ferramentas e Termux | `agent/AgentViewModel.kt`, `agent/McpClient.kt` | Preservados; autorização de ações sensíveis continua obrigatória. |
| Provedores OpenAI, NVIDIA, OpenRouter | Ausentes no caminho principal de inferência | Criado chat online isolado com endpoint oficial de cada fornecedor. |

## Alterações da Fase 1

- Novo chat **opt-in online**, acessível pela home ou pelas configurações.
- Seleção explícita de provedor e identificador de modelo; `/chat/completions` via HTTPS.
- Credenciais cifradas com AES/GCM e Android Keystore, persistidas somente em `noBackupFilesDir`.
- Requisições apenas para três hosts fixos; redirecionamentos desligados.
- Somente texto do chat online e até 12 mensagens recentes são transmitidos após `Enviar`.
- Nenhum documento, foto, áudio, histórico offline, RAG ou segredo é automaticamente enviado.
- O chat remoto fica somente na memória da tela; limpar ou sair elimina as mensagens da sessão.
- Erros HTTP exibidos de forma genérica, sem exibir corpo da resposta nem tokens.
- Interface de conversa offline original não é modificada.

## Limitações claras desta entrega

1. A alternância entre Gemma 4 local e chat online é **manual**, não roteamento automático.
2. Whisper, TTS e Stable Diffusion permanecem nos módulos locais já existentes; ainda não são encadeados ao chat online.
3. O chat online aceita **texto**, não anexos de imagem, áudio ou vídeo.
4. Sem histórico persistente do chat online nem descoberta automática de modelos.
5. Provedores podem exigir créditos pagos; a presença de API key não garante acesso gratuito ou a um modelo específico.
6. O app tem licença PolyForm Noncommercial 1.0.0. Este fork deve ser utilizado apenas para testes pessoais não comerciais sem licença comercial separada.

## Segurança e próximos itens (Fases 2–4)

- Revisar tratamento de backup de todo o app, inclusive tokens legados; escopo atual protege apenas novas chaves híbridas.
- Seleção de modelo local Gemma 4 E2B pela interface normal de modelos.
- Validar Whisper Multilingual em PT-BR, TTS do aparelho ou voz Kokoro compatível e permissões de microfone.
- Fazer fluxos híbridos de voz (ASR → texto → resposta → TTS) com autorização explícita do envio online.
- Geração local de imagem com fallback não automático e aviso de custo antes de qualquer API externa.
- Retentativas limitadas, cancelamento, histórico opcional criptografado e testes instrumentados de segurança.
- Não conectar MCP a provedores online sem confirmação por ferramenta.

## Checklist de verificação manual

- [ ] Abrir o Chat IA antigo: conversar offline e carregar Gemma 4 normalmente.
- [ ] Abrir o Assistente híbrido a partir da home/configurações.
- [ ] Selecionar cada provedor e digitar um identificador válido de modelo.
- [ ] Salvar chave; verificar texto mascarado e remoção.
- [ ] Enviar pergunta simples online; ver resposta de texto.
- [ ] Testar sem rede, erro 401/403 e limite 429; nenhuma chave deve aparecer em logs ou tela.
- [ ] Alternar provedor: conversa anterior limpa, evitando transmissão cruzada de mensagens.
- [ ] Reabrir o app: chave continua protegida; histórico remoto não é restaurado.
- [ ] Verificar que Whisper/TTS, geração de imagem e rotas existentes continuam funcionando.
- [ ] Inspecionar `android:noBackupFilesDir` e backup; não registrar tokens.
- [ ] Testar em um aparelho ARM64 real antes de qualquer merge.

### Referências dos provedores
- OpenAI Chat Completions: https://platform.openai.com/docs/api-reference/chat/create
- NVIDIA NIM Chat: https://docs.api.nvidia.com/nim/reference/llm-apis
- OpenRouter API: https://openrouter.ai/docs/api-reference/chat-completion

Sem merge automático. O PR será revisado antes de qualquer alteração na `main`.
