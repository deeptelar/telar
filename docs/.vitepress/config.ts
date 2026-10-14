import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

// The site lives in a folder of deeptelar.github.io. To move it to its own domain, change these two.
const base = '/telar/'
const site = `https://deeptelar.github.io${base}`

const repository = 'https://github.com/deeptelar/telar'
const demo = 'https://deeptelar.github.io/telar-demo/'
// Dokka builds the API reference, and the workflow that publishes the site puts it in this folder.
const api = `${site}api/`
const description =
  'AI agents and workflows for Kotlin, as small typed graphs. Loops, parallel steps, streaming, save points and human approval, on every Kotlin platform.'

// The latest release is the first version with a section in the changelog.
const changelog = readFileSync(fileURLToPath(new URL('../../CHANGELOG.md', import.meta.url)), 'utf8')
const release = changelog.match(/^## \[(\d[^\]]*)\]/m)?.[1] ?? 'Releases'

export default withMermaid(
  defineConfig({
    lang: 'en',
    title: 'Telar',
    description,
    base,
    cleanUrls: true,

    // docs/brand holds the logo files. They are served as they are, and its README is not a page.
    srcExclude: ['brand/**'],
    vite: { publicDir: fileURLToPath(new URL('../brand', import.meta.url)) },
    // GitHub shows the README of a folder, and a site shows its index.
    rewrites: { 'tutorial/README.md': 'tutorial/index.md' },

    sitemap: { hostname: site },
    markdown: { theme: { light: 'solarized-light', dark: 'vitesse-dark' } },
    // The colors of a diagram come from the style sheet of the theme, in both modes.
    mermaid: { fontFamily: "'Geist Mono', ui-monospace, monospace" },
    head: [
      ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
      ['link', { rel: 'icon', sizes: '32x32', href: `${base}favicon.ico` }],
      ['meta', { property: 'og:type', content: 'website' }],
      ['meta', { property: 'og:title', content: 'Telar' }],
      ['meta', { property: 'og:description', content: description }],
      ['meta', { property: 'og:image', content: `${site}social-preview-1280x640.png` }],
      ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ],

    themeConfig: {
      // The lockup has the wordmark, so the title is not written next to it.
      logo: { light: '/telar-lockup.svg', dark: '/telar-lockup-on-dark.svg', alt: 'Telar' },
      siteTitle: false,
      nav: [
        { text: 'Get started', link: '/quick-start' },
        { text: 'Guides', link: '/guides/', activeMatch: '^/guides/' },
        { text: 'Tutorial', link: '/tutorial/', activeMatch: '^/tutorial/' },
        { text: 'API', link: api },
        {
          text: release,
          items: [
            { text: 'Changelog', link: `${repository}/blob/main/CHANGELOG.md` },
            { text: 'Roadmap', link: `${repository}/blob/main/ROADMAP.md` },
            { text: 'Contributing', link: `${repository}/blob/main/CONTRIBUTING.md` },
          ],
        },
      ],
      sidebar: [
        {
          text: 'Start here',
          items: [
            { text: 'Why Telar', link: '/why-telar' },
            { text: 'Quick start', link: '/quick-start' },
            { text: 'Installation', link: '/installation' },
          ],
        },
        {
          text: 'Guides',
          items: [
            { text: 'Overview', link: '/guides/' },
            { text: 'Streaming', link: '/guides/streaming' },
            { text: 'Human-in-the-loop', link: '/guides/human-in-the-loop' },
            { text: 'Parallel branches', link: '/guides/parallel-branches' },
            { text: 'Loops', link: '/guides/loops' },
            { text: 'Subgraphs', link: '/guides/subgraphs' },
            { text: 'AI models', link: '/guides/ai-models' },
            { text: 'Decision models', link: '/guides/decision-models' },
            { text: 'Agents with tools', link: '/guides/agents-with-tools' },
            { text: 'Tools from MCP servers', link: '/guides/mcp' },
            { text: 'Inspecting a graph', link: '/guides/inspecting-a-graph' },
            { text: 'Retries and time limits', link: '/guides/retries' },
            { text: 'Tracing', link: '/guides/tracing' },
            { text: 'Testing', link: '/guides/testing' },
            { text: 'Errors', link: '/guides/errors' },
          ],
        },
        {
          text: 'Tutorial',
          items: [
            { text: 'The levels', link: '/tutorial/' },
            { text: 'The idea', link: '/tutorial/00-the-idea' },
            { text: '1. A line of nodes', link: '/tutorial/01-a-line-of-nodes' },
            { text: '2. Choices', link: '/tutorial/02-choices' },
            { text: '3. Loops', link: '/tutorial/03-loops' },
            { text: '4. Two things at once', link: '/tutorial/04-two-things-at-once' },
            { text: '5. Save points', link: '/tutorial/05-save-points' },
            { text: '6. The agent', link: '/tutorial/06-the-agent' },
            { text: '7. Game over screens', link: '/tutorial/07-game-over-screens' },
            { text: '8. Your own workflow', link: '/tutorial/08-your-own-workflow' },
            { text: 'Cheat sheet', link: '/tutorial/cheat-sheet' },
          ],
        },
        {
          text: 'More',
          items: [
            { text: 'Samples', link: '/samples' },
            { text: 'API reference', link: api },
            { text: 'Pixel Pizza, the demo', link: demo },
          ],
        },
      ],
      outline: [2, 3],
      search: { provider: 'local' },
      editLink: { pattern: `${repository}/edit/main/docs/:path`, text: 'Edit this page on GitHub' },
      socialLinks: [{ icon: 'github', link: repository }],
      footer: {
        message: 'Released under the Apache License 2.0.',
        copyright:
          'Telar is an independent project inspired by LangGraph. It is not affiliated with or endorsed by LangChain, Inc.',
      },
    },
  }),
)
