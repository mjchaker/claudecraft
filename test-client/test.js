const mineflayer = require('mineflayer');

const bot = mineflayer.createBot({
  host: 'localhost',
  port: 25565,
  username: 'Tester',
  version: '1.20.4',
});

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

bot.on('message', (jsonMsg) => log('CHAT |', jsonMsg.toString()));
bot.on('kicked', (r) => log('KICKED |', JSON.stringify(r)));
bot.on('error', (e) => log('ERROR |', e.message));

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

bot.once('spawn', async () => {
  log('-- spawned in world');
  await wait(2000);

  log('-- test 1: chat trigger "@claude ..."');
  bot.chat('@claude hello, can you hear me?');
  await wait(8000);

  log('-- test 2: /ask command');
  bot.chat('/ask what is 2+2?');
  await wait(8000);

  log('-- test 3: /bot spawn');
  bot.chat('/bot spawn');
  await wait(3000);

  log('-- test 4: /bot status');
  bot.chat('/bot status');
  await wait(2000);

  log('-- test 5: /bot task');
  bot.chat('/bot task build a 5x5 stone platform right next to you');
  await wait(15000);

  log('-- test 6: /bot status during/after task');
  bot.chat('/bot status');
  await wait(2000);

  log('-- test 7: /bot despawn');
  bot.chat('/bot despawn');
  await wait(2000);

  log('-- done, disconnecting');
  bot.quit();
  process.exit(0);
});
