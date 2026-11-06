import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = process.argv[2];
if (!root) throw new Error('Usage: node install.mjs /path/to/austin');
const base = path.join(root, 'austin-handler/src/main/java/com/java3y/austin/handler');
const file = path.join(base, 'handler/impl/SmsHandler.java');
let source = fs.readFileSync(file, 'utf8');
const marker = '    private static final Integer AUTO_FLOW_RULE = 0;';
const hook = '                smsParam.setScriptName(messageTypeSmsConfig.getScriptName());';
if (!source.includes('outboundSmsGateBridge.dispatch')) {
  if (source.split(marker).length !== 2 || source.split(hook).length !== 2)
    throw new Error('Austin SmsHandler has changed; review the upstream adapter contract before installing.');
  source = source.replace(marker, `${marker}\n\n    @Autowired\n    private com.java3y.austin.handler.script.impl.OutboundSmsGateBridge outboundSmsGateBridge;`)
    .replace(hook, `                if ("OutboundSmsGateScript".equals(messageTypeSmsConfig.getScriptName())) {\n                    return outboundSmsGateBridge.dispatch(taskInfo.getBizId());\n                }\n${hook}`);
  fs.writeFileSync(file, source);
}
fs.copyFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'OutboundSmsGateBridge.java'),
  path.join(base, 'script/impl/OutboundSmsGateBridge.java'));
console.log('Installed OutboundSmsGateScript handoff in Austin SmsHandler. Configure a dedicated SMS account with this script name.');
