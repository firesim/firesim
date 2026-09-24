import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';
import { createReadStream, readFileSync, statSync } from 'node:fs';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { sidebar } from './src/sidebar.mjs';

const sphinxPublicRoot = resolve(fileURLToPath(new URL('./public/sphinx/', import.meta.url)));
const sphinxContentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.gif': 'image/gif',
  '.html': 'text/html; charset=utf-8',
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

function serveSphinxAssets() {
  return {
    name: 'serve-sphinx-assets',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url || '/', 'http://localhost').pathname;
        if (!pathname.startsWith('/sphinx/')) return next();

        let relativePath;
        try {
          relativePath = decodeURIComponent(pathname.slice('/sphinx/'.length));
        } catch {
          return next();
        }

        const filePath = resolve(sphinxPublicRoot, relativePath);
        if (!filePath.startsWith(sphinxPublicRoot + sep)) return next();

        let file;
        try {
          file = statSync(filePath);
        } catch {
          return next();
        }
        if (!file.isFile()) return next();

        response.statusCode = 200;
        response.setHeader('Content-Length', file.size);
        response.setHeader(
          'Content-Type',
          sphinxContentTypes[extname(filePath).toLowerCase()] || 'application/octet-stream',
        );
        createReadStream(filePath).on('error', next).pipe(response);
      });
    },
  };
}

function getBasePath() {
  if (process.env.DOCS_BASE) return process.env.DOCS_BASE;
  if (process.env.READTHEDOCS !== 'True') return undefined;

  const language = process.env.READTHEDOCS_LANGUAGE || 'en';
  const version = process.env.READTHEDOCS_VERSION || 'latest';
  return `/${language}/${version}`;
}

const base = getBasePath();
const basePrefix = base ? base.replace(/\/$/, '') : '';
const legacyRedirectRoutes = JSON.parse(
  readFileSync(new URL('./_build/starlight-legacy-routes.json', import.meta.url), 'utf8'),
);
const legacyRedirects = Object.fromEntries(
  Object.entries(legacyRedirectRoutes).map(([from, to]) => [from, `${basePrefix}${to}`]),
);

export default defineConfig({
  site: process.env.DOCS_SITE_URL || 'https://docs.fires.im',
  base,
  vite: {
    plugins: [serveSphinxAssets()],
  },
  redirects: legacyRedirects,
  integrations: [
    starlight({
      title: 'FireSim',
      description: 'FireSim documentation',
      favicon: '/favicon/favicon.svg',
      logo: {
        dark: './_static/firesim_logo_small_darkmode.png',
        light: './_static/firesim_logo_small.png',
        alt: 'FireSim',
        replacesTitle: true,
      },
      components: {
        Sidebar: './src/components/Sidebar.astro',
      },
      customCss: ['./src/styles/sphinx.css'],
      expressiveCode: {
        shiki: {
          // Preserve Sphinx's lexer identifiers in generated fences, and teach
          // Shiki only about identifiers without a directly matching grammar.
          langAlias: {
            C: 'c',
            Scala: 'scala',
            'c++': 'cpp',
            Verilog: 'verilog',
            default: 'text',
            dts: 'c',
            kconfig: 'shell',
            none: 'text',
            shell: 'bash',
          },
        },
      },
      sidebar,
      tableOfContents: {
        minHeadingLevel: 2,
        maxHeadingLevel: 4,
      },
      head: [
        {
          tag: 'link',
          attrs: {
            rel: 'icon',
            type: 'image/png',
            sizes: '96x96',
            href: `${basePrefix}/favicon/favicon-96x96.png`,
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'shortcut icon',
            href: `${basePrefix}/favicon/favicon.ico`,
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'apple-touch-icon',
            sizes: '180x180',
            href: `${basePrefix}/favicon/apple-touch-icon.png`,
          },
        },
        {
          tag: 'meta',
          attrs: {
            name: 'apple-mobile-web-app-title',
            content: 'FireSim',
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'manifest',
            href: `${basePrefix}/favicon/site.webmanifest`,
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'stylesheet',
            href: `${basePrefix}/sphinx/current/_static/tabs.css`,
          },
        },
        {
          tag: 'script',
          attrs: {
            src: `${basePrefix}/sphinx/current/_static/tabs.js`,
            defer: true,
          },
        },
        {
          tag: 'meta',
          attrs: {
            name: 'readthedocs-addons-api-version',
            content: '1',
          },
        },
        {
          tag: 'script',
          attrs: {
            src: `${basePrefix}/readthedocs-version-switcher.js`,
            defer: true,
          },
        },
      ],
    }),
  ],
});
