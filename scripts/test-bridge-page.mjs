import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Script, createContext } from 'node:vm';

// Check the actual embedded JS, including the Kotlin-escaped gateway placeholder.
const source = readFileSync(new URL('../app/src/main/java/com/dingaimin/dedaocompanion/DedaoBridgeServer.kt', import.meta.url), 'utf8');
const js = source.match(/<script>([\s\S]*?)<\/script>/)[1].replaceAll("${'$'}", '$');
const script = new Script(js);

async function scenario({ fail = false, nativeThrows = false } = {}) {
  const calls = [], requests = [], timers = [], elements = new Map();
  const context = createContext({
    window: { WebViewJavascriptBridge: { send(message, callback) {
      calls.push(message);
      if (message.sdkType === 'network.load') {
        if (message.data.url.endsWith('/bauhinia/v1/class/player/info')) {
          callback({ data: { red_packet_expire_day: '9月22日' } });
        } else {
          callback({ items: [{
            authority_intro: { red_packet_rights: true }, id: '1073', product_id: '1073',
            product_title: '测试课程', product_type: 66, is_article_collection: true,
            article_item: { id: '6004897', product_title: '测试条目', product_type: 65,
              resource: { audio_id: 'test-audio' } },
          }] });
        }
      }
      if (message.sdkType === 'jump.universal' && nativeThrows) throw Error('unsupported');
    } } },
    document: { getElementById(id) {
      if (!elements.has(id)) elements.set(id, { style: {} });
      return elements.get(id);
    } },
    location: { href: '' },
    fetch: async (url, options) => { requests.push({ url, options }); return { ok: !fail }; },
    setTimeout: (fn, ms) => { if (ms === 250) timers.push(fn); return 1; },
    clearTimeout() {},
    setInterval() { return 1; },
    clearInterval() {},
  });
  script.runInContext(context);
  for (let i = 0; i < 30; i++) await Promise.resolve();
  assert.equal(timers.length, 1);
  timers[0]();
  assert.equal(calls[0].data.url, '$_ENTREE_DOMAIN_$/mustard-view/v1/red_packet/category/list');
  assert.ok(calls.some(c => c.data?.url === '$_ENTREE_DOMAIN_$/bauhinia/v1/class/player/info'));
  const target = `dedaocompanion://bridge/${fail ? 'failed' : 'complete'}`;
  const navigation = calls.find(c => c.sdkType === 'jump.universal');
  assert.equal(navigation.data.type, 'scheme');
  assert.equal(navigation.data.route, target);
  assert.ok(requests.some(r => r.url.startsWith('/result/')));
  const submitted = JSON.parse(requests.find(r => r.url.startsWith('/result/')).options.body);
  assert.equal(submitted.items[0].expiresOn, '9月22日');
  assert.equal('parentId' in submitted.items[0], false);
  if (nativeThrows) assert.equal(context.location.href, target);
  else assert.equal(context.location.href, '');
  let prevented = false;
  elements.get('r').onclick({ preventDefault() { prevented = true; } });
  assert.ok(prevented);
}

await scenario();
await scenario({ fail: true });
await scenario({ nativeThrows: true });
console.log('Bridge page: success, failure, manual retry and native fallback passed');
