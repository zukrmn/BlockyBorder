# BlockyBorder

**BlockyBorder** é um plugin para servidores Minecraft Beta 1.7.3 que implementa borda contínua (wrap-around), pré-carregamento de chunks e integração para mapas retangulares sem barreira rígida. Ideal para mapas baseados no mundo real ou mundos customizados com Dynmap.

## Funcionalidades

- **Borda Contínua:** Jogadores que chegam à borda do mundo são teleportados para o lado oposto, simulando mapa infinito.
- **Modo Clássico/Barreira:** Permite optar por bloqueio tradicional de borda.
- **Pré-carregamento de chunks:** Comando `/fill` para gerar todos os chunks dentro da borda, incluindo árvores e relevo.
- **Configuração Totalmente Personalizável:** Limites da borda definidos por coordenadas exatas via `config.properties`.
- **Compatível com Dynmap:** Após `/fill`, execute `/dynmap fullrender` para gerar renderização completa das áreas previamente vazias.

## Instalação

1. Compile e coloque o arquivo `BlockyBorder.jar` na pasta `plugins` do servidor Uberbukkit 1.7.3.
2. Inicie o servidor para gerar o arquivo de configuração `config.properties`.
3. Edite o arquivo `config.properties` e ajuste as coordenadas da borda conforme seu mapa.
4. Reinicie o servidor.

## Configuração

Exemplo de `config.properties`:

```
buffer=2.0
loop=true
x2=5376
x1=-5376
enabled=true
z2=2688
z1=-2688
```

- `loop=true`: ativa wrap-around (teleporte nas bordas).
- `buffer`: distância extra ao teleportar (evita cair fora do mapa).

## Comandos

- `/fill <freq> <pad>`
  - **freq:** chunks por tick (recomendado: 30–50 em servidor dedicado).
  - **pad:** padding extra além das bordas (ex: 0 ou 2).
  - Executa pré-geração dos terrenos, evitando lag posterior e preparando o mundo para Dynmap.

## Exemplo de uso

1. Defina os limites em `config.properties`.
2. Execute `/fill 40 2` no console para gerar todos os chunks.
3. Veja o progresso dos chunks pelo console.
4. Após o processo, rode `/dynmap fullrender` para visualizar o mapa totalmente preenchido.

## Integração

Totalmente compatível com plugins de mapa como Dynmap e esquemas de mundo baseados em coordenadas reais.

## Reportar bugs ou requisitar features

Reporte bugs ou sugira novas funcionalidades na seção [Issues](https://github.com/andradecore/BlockyBorder/issues) do projeto.

## Contato:

- Discord: https://discord.gg/tthPMHrP
