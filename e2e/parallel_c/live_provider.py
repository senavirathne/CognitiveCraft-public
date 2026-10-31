"""Qualify genuine phrasing on the pinned shared or CI-managed llama.cpp Docker backend."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import urllib.parse
import urllib.request

from common import HERE, JAR, ROOT, EvidenceError, check, digest, identity, no_duplicates, read, write
from blocker_runtime import dependency

ENV = ('COGNITIVECRAFT_C_MODEL_URL', 'COGNITIVECRAFT_C_MODEL_ALIAS', 'COGNITIVECRAFT_C_MODEL_SHA256',
       'COGNITIVECRAFT_C_MODEL_FILE', 'COGNITIVECRAFT_C_MODEL_SERVER_PATH', 'COGNITIVECRAFT_C_MODEL_BUILD')


def get_properties(base):
    with urllib.request.urlopen(base.rstrip('/') + '/props', timeout=5) as response:
        data = response.read(131073)
    check(len(data) <= 131072, 'bounded existing-backend metadata')
    value = json.loads(data)
    return {key: value.get(key) for key in ('model_path', 'chat_template', 'build_info')}


def verify(output, registry):
    output = Path(output); receipt = read(output / 'live.json')
    check(receipt.get('status') != 'BLOCKED_DEPENDENCY', receipt.get('error', 'configured genuine backend unavailable'))
    model = ROOT / receipt['model_file']
    check(model.is_relative_to(ROOT / 'build/e2e-sdk/models') or model.is_relative_to(ROOT / 'build/parallel-c-cache/models'), 'existing model-cache path required')
    check(digest(model) == receipt['model_sha256'], 'configured model changed after live execution')
    expected_tokenizer = hashlib.sha256((receipt['model_sha256'] + '\n' + receipt['chat_template']).encode()).hexdigest()
    check(expected_tokenizer == receipt['tokenizer_sha256'], 'tokenizer/model/chat-template identity mismatch')
    check(receipt['before'] == receipt['after'], 'shared backend changed during the response campaign')
    check(receipt['before']['model_path_sha256'] == receipt['configured_server_path_sha256'], 'configured model path mismatch')
    check(receipt['before']['build_info'] == receipt['configured_build'], 'configured shared runtime build mismatch')
    check(receipt['before']['chat_template_sha256'] == hashlib.sha256(receipt['chat_template'].encode()).hexdigest(), 'recorded chat template differs from observed backend')
    check(receipt['jar_sha256'] == digest(JAR), 'genuine probe JAR changed')
    check(receipt['driver_classes'], 'measured actual live driver bytecode required')
    if receipt.get('backend_profile') == 'PINNED_CI_BACKEND':
        bootstrap = read(output / 'managed-backend.json'); lock = read(HERE / 'ci/model-lock.json')
        check(bootstrap['status'] == 'READY' and bootstrap['lock'] == lock and bootstrap['lock_sha256'] == digest(HERE / 'ci/model-lock.json'), 'managed backend source/model lock mismatch')
        check(bootstrap['dockerfile_sha256'] == digest(HERE / 'ci/ModelDockerfile') and bootstrap['model_sha256'] == receipt['model_sha256'], 'managed model build provenance mismatch')
        check(re.fullmatch(r'sha256:[0-9a-f]{64}', bootstrap['image_id']) and re.fullmatch(r'[0-9a-f]{64}',bootstrap['server_binary_sha256']), 'measured server image/binary identity required')
        check(bootstrap['lock']['build'] == receipt['configured_build'], 'managed server build mismatch')
    for path, sha in receipt['driver_classes'].items():
        data = (output / path).read_bytes()
        check(digest(output / path) == sha and data[:4] == b'\xca\xfe\xba\xbe' and struct.unpack_from('>H', data, 6)[0] == 69, 'live driver class changed/not Java 25')
    rows = read(output / 'responses.json')
    check({r['case_id'] for r in rows} == {r['id'] for r in registry['provider_cases']} and len(rows) == len(registry['provider_cases']), 'missing/duplicate genuine response case')
    for expected in registry['provider_cases']:
        row = next(r for r in rows if r['case_id'] == expected['id'])
        check(row['tier'] == 'GENUINE_MODEL_SYNTHETIC_FACTS', 'wrong genuine response tier')
        check(row['production_jar_sha256'] == receipt['jar_sha256'], 'live wrapper did not load from the measured production JAR')
        check(row['fact_packet'] == expected['facts'] and row['allowed_replies'] == expected['replies'], 'live fixture facts/phrasing changed')
        facts_digest = hashlib.sha256((json.dumps(expected['facts'], sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode()).hexdigest()
        check(row['facts_sha256'] == facts_digest, 'live fact digest changed')
        check(row['deliverable'] and row['reason'] in ('MODEL', 'INVALID_RESPONSE') and row['text'] in expected['replies'], 'actual provider response/fallback did not satisfy grounding')
        check(row['model_sha256'] == receipt['model_sha256'] and row['tokenizer_sha256'] == expected_tokenizer, 'response model/tokenizer mismatch')
        check(row['actual_input_tokens'] is None and row['actual_output_tokens'] is None and row['compute_termination_confirmed'] is False, 'SPI usage/termination must remain unknown')
        check(1 <= row['provider_calls'] <= 2 and row['reserved_input_tokens'] <= 2048 and row['reserved_output_tokens'] <= 256, 'response reservation limit')
        check(0 <= row['elapsed_ns'] <= 10_000_000_000, 'ten-second response deadline exceeded')
        wire = read(output / row['wire']); tokenizations, generations = wire['tokenizations'], wire['generations']
        check(len(tokenizations) == len(generations) == row['provider_calls'], 'unmeasured or incomplete genuine call')
        input_tokens = output_tokens = reserved = 0
        for tokenization, generation in zip(tokenizations, generations):
            check(tokenization['model_sha256'] == receipt['model_sha256'] and tokenization['tokenizer_sha256'] == expected_tokenizer, 'tokenizer receipt identity')
            check(hashlib.sha256(tokenization['prompt'].encode()).hexdigest() == tokenization['prompt_sha256'], 'tokenized prompt bytes changed')
            check(len(tokenization['tokens']) == tokenization['count'] and 1 <= tokenization['count'] <= 1024
                  and all(type(t) is int and t >= 0 for t in tokenization['tokens']), 'actual tokenization result required')
            request, response = generation['request'], generation['response']
            check(request['messages'] == tokenization['messages'] and request['model'] == receipt['model_alias'] and request['max_tokens'] == 128, 'tokenized/generated prompt mismatch')
            expected_schema = {'type': 'object', 'properties': {'text': {'type': 'string', 'enum': expected['replies']},
                'facts_sha256': {'type': 'string', 'const': facts_digest}}, 'required': ['text', 'facts_sha256'], 'additionalProperties': False}
            check(request['response_format'] == {'type': 'json_object', 'schema': expected_schema}, 'pinned server grammar field/schema mismatch')
            check(200 <= generation['http_status'] < 300, 'genuine backend HTTP failure')
            usage = response.get('usage', {})
            observed_input, observed_output = usage.get('prompt_tokens'), usage.get('completion_tokens')
            check(type(observed_input) is int and type(observed_output) is int and 0 < observed_input <= tokenization['count'] and 0 <= observed_output <= 128, 'actual backend usage missing or over reservation')
            input_tokens += observed_input; output_tokens += observed_output; reserved += tokenization['count']
        check(input_tokens <= reserved == row['reserved_input_tokens'] and output_tokens <= row['reserved_output_tokens'], 'cumulative genuine response budget')
        final = generations[-1]['response']['choices'][0]
        check(final['finish_reason'] == 'stop', 'actual generation did not finish within its existing token limit')
        try:
            candidate = json.loads(final['message']['content'], object_pairs_hook=no_duplicates)
            valid = isinstance(candidate, dict) and set(candidate) == {'text', 'facts_sha256'} and candidate['text'] in expected['replies'] and candidate['facts_sha256'] == facts_digest
        except (ValueError, TypeError): valid = False
        check((row['reason'] == 'MODEL') == valid, 'model/fallback disposition disagrees with actual generated content')
        if valid: check(row['text'] == candidate['text'], 'delivered phrasing differs from actual generated text')
    return {'status': 'PASS', 'planned': len(rows), 'executed': len(rows), 'passed': len(rows), 'failed': 0,
            'refused_expected': 0, 'inconclusive': 0, 'model_sha256': receipt['model_sha256'], 'tokenizer_sha256': expected_tokenizer,
            'accepted_model_responses': sum(r['reason'] == 'MODEL' for r in rows),
            'fallback_after_real_generation': sum(r['reason'] == 'INVALID_RESPONSE' for r in rows),
            'provider_calls': sum(r['provider_calls'] for r in rows),
            'scope': 'GENUINE_GENERATION_WITH_SYNTHETIC_FACTS; zero native/client/settlement credit'}


def run(output):
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    registry = read(HERE / 'registries/C03.json')
    record = {**identity(), 'jar_sha256': digest(JAR) if JAR.is_file() else None, 'status': 'BLOCKED_DEPENDENCY', 'planned': len(registry['provider_cases']), 'executed': 0, 'passed': 0, 'failed': 0,
              'inconclusive': 0, 'refused_expected': 0, 'error': None, 'model_sha256': None, 'tokenizer_sha256': None}
    try:
        values = {key: os.environ.get(key, '') for key in ENV}
        check(not os.environ.get('COGNITIVECRAFT_C_MODEL_CONFIG_ERROR'), os.environ.get('COGNITIVECRAFT_C_MODEL_CONFIG_ERROR', 'model configuration unavailable'))
        check(all(values.values()), 'pinned backend/model/tokenizer unavailable; inspect host backend bootstrap logs')
        base = values[ENV[0]]; uri = urllib.parse.urlsplit(base)
        check(uri.scheme == 'https' or uri.scheme == 'http' and uri.hostname in ('localhost', '127.0.0.1', '::1'), 'HTTPS or explicit loopback model service required')
        check(uri.hostname is not None and not uri.username and not uri.password and not uri.query and not uri.fragment and uri.path in ('', '/'), 'credential-free root backend URL required')
        check(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}', values[ENV[1]]) is not None, 'explicit model alias required')
        check(re.fullmatch(r'[0-9a-f]{64}', values[ENV[2]]) is not None, 'explicit model SHA256 required')
        relative = Path(values[ENV[3]]); check(not relative.is_absolute() and '..' not in relative.parts, 'relative existing model-cache file required')
        model = (ROOT / relative).resolve()
        check(model.is_relative_to(ROOT / 'build/e2e-sdk/models') or model.is_relative_to(ROOT / 'build/parallel-c-cache/models'), 'existing model-cache file required')
        with model.open('rb') as stream: check(stream.read(4) == b'GGUF', 'actual configured GGUF required')
        check(digest(model) == values[ENV[2]], 'configured GGUF digest mismatch')
        before = get_properties(base)
        record['backend_profile'] = os.environ.get('COGNITIVECRAFT_C_BACKEND_PROFILE', 'EXISTING_PINNED_BACKEND')
        if record['backend_profile'] == 'PINNED_CI_BACKEND':
            shutil.copy2(ROOT / 'build/e2e/parallel-c/backend/backend.json', output / 'managed-backend.json')
        check(before['model_path'] == values[ENV[4]] and before['build_info'] == values[ENV[5]] and isinstance(before['chat_template'], str), 'configured shared backend model/build/template mismatch')
        tokenizer = hashlib.sha256((values[ENV[2]] + '\n' + before['chat_template']).encode()).hexdigest()
        def whitelisted(properties):
            return {'model_path_sha256': hashlib.sha256(properties['model_path'].encode()).hexdigest(), 'build_info': properties['build_info'],
                    'chat_template_sha256': hashlib.sha256(properties['chat_template'].encode()).hexdigest()}
        record.update(model_file=str(relative), model_sha256=values[ENV[2]], model_alias=values[ENV[1]], tokenizer_sha256=tokenizer,
                      chat_template=before['chat_template'], configured_server_path_sha256=hashlib.sha256(values[ENV[4]].encode()).hexdigest(),
                      configured_build=values[ENV[5]], before=whitelisted(before), backend_url_sha256=hashlib.sha256(base.encode()).hexdigest())
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        driver = output / 'driver-classes/dev/aivillages/providers/interaction'; driver.mkdir(parents=True)
        classes = list((ROOT/'providers/build/classes/java/test/dev/aivillages/providers/interaction').glob('ConversationLiveProbe*.class'))
        check(classes, 'actual compiled live driver required')
        for source in classes: shutil.copy2(source, driver/source.name)
        record['driver_classes'] = {p.relative_to(output).as_posix(): digest(p) for p in driver.glob('*.class')}
        cp = os.pathsep.join([str(JAR), str(output/'driver-classes'), dependency()['path']])
        command = [java, '-cp', cp, 'dev.aivillages.providers.interaction.ConversationLiveProbe', base, values[ENV[1]], values[ENV[2]], tokenizer, str(output)]
        with (output / 'process.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=100)
        record.update(exit=result.returncode, after=whitelisted(get_properties(base)), status='MEASURED', error=None)
        write(output / 'live.json', record)
        check(result.returncode == 0, 'actual provider driver failed; see process.log')
        totals = verify(output, registry); record.update(totals); write(output / 'live.json', record)
    except (EvidenceError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as failure:
        if isinstance(failure, KeyError): record['missing_receipt_field'] = str(failure.args[0])[:96]
        record['error'] = str(failure) if isinstance(failure, EvidenceError) else type(failure).__name__ + ': shared backend evidence unavailable'
        record['status'] = 'BLOCKED_DEPENDENCY'
        if (output / 'responses.json').is_file():
            record['executed'] = sum(r.get('provider_calls', 0) > 0 for r in read(output/'responses.json')); record['inconclusive'] = record['executed']
        write(output / 'live.json', record)
    return record


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--output', type=Path, required=True); a = p.parse_args()
    result = run(a.output); print(json.dumps(result, sort_keys=True)); raise SystemExit(0 if result['status'] == 'PASS' else 2)
