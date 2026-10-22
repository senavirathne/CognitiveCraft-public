#!/usr/bin/env python3
"""Reconcile bounded raw planning transport evidence without imposing a quality threshold."""
import argparse
import collections
import hashlib
import json
from pathlib import Path

MODEL_DIGESTS = {
    'qwen2.5:1.5b-instruct': '65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b',
    'qwen2.5:3b-instruct': '357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b',
}


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def check_row(row):
    require(row['nativeEffects'] == row['dedicatedModels'] == 0, 'Preview scope changed')
    require(row['calls'] == len(row['attempts']) <= 2 and row['repairs'] == len(row['attempts']) - 1,
            'Correction allowance changed')
    for attempt in row['attempts']:
        transport = attempt['transport']
        request = json.loads(transport['request'])
        require(sha(transport['request']) == transport['requestSha256'], 'Request hash mismatch')
        require(len(transport['request'].encode()) == attempt['inputBytes'] <= 16384, 'Request byte accounting mismatch')
        require(sha(transport['response']) == transport['wireSha256'], 'Response hash mismatch')
        require(len(transport['response'].encode()) == transport['wireBytes'] <= 65536, 'Wire bound mismatch')
        require(not transport['overflow'] and transport['httpStatus'] == 200, 'Incomplete transport')
        require(request['options'] == {'temperature': 0, 'num_predict': 768} and request['stream'] is False,
                'Generation configuration changed')
        require(request['model'] == row['model'] and request['messages'][-1]['content'] == attempt['prompt'],
                'Prompt/model identity mismatch')
        shape = json.dumps(request['format'], sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        require(sha(shape) == attempt['constraintSha256'], 'Actual constraint differs from recorded constraint')
        envelope = json.loads(transport['response'])
        raw = envelope['message']['content']
        require(raw == transport['rawCandidate'] and sha(raw) == transport['rawCandidateSha256'], 'Raw candidate mismatch')
        require(len(raw.encode()) == transport['rawCandidateBytes'] == attempt['outputBytes'] <= 8192,
                'Output byte accounting mismatch')
        require(envelope['prompt_eval_count'] == attempt['promptTokens'] > 0 and
                0 < envelope['eval_count'] == attempt['outputTokens'] <= 768, 'Missing actual bounded token counts')
        require(envelope['done'] is True and envelope['model'] == row['model'], 'Wrong/incomplete backend response')
        if attempt['outcome'] == 'CANDIDATE':
            require(raw == attempt['candidate'] and transport['decodeCode'] == 'NONE' and
                    envelope.get('done_reason', 'stop') == 'stop', 'Rejected bytes promoted into candidate IR')
        else:
            require(attempt['candidate'] == 'unavailable' and not attempt['valid'], 'Rejected bytes admitted')


def summarize(rows):
    attempts = [a for r in rows for a in r['attempts']]
    return {'requests': len(rows), 'firstPassValid': sum(r['firstPassValid'] for r in rows),
            'finalValid': sum(r['valid'] for r in rows), 'calls': sum(r['calls'] for r in rows),
            'repairs': sum(r['repairs'] for r in rows),
            'outcomes': dict(collections.Counter(a['outcome'] for a in attempts)),
            'decoderDiagnostics': dict(collections.Counter(a['transport'].get('decodeCode', 'UNAVAILABLE') for a in attempts)),
            'doneReasons': dict(collections.Counter(a['transport']['doneReason'] for a in attempts)),
            'failedCases': [{'caseId': r['caseId'], 'diagnostic': r['attempts'][-1]['diagnostic'],
                             'path': r['attempts'][-1]['path']} for r in rows if not r['valid']]}


def review(root, head, supplemental_count):
    original = [json.loads(line) for p in root.rglob('*.jsonl') for line in p.read_text().splitlines() if line]
    extra = [json.loads(line) for p in root.rglob('*.ndjson') for line in p.read_text().splitlines() if line]
    models = ('qwen2.5:1.5b-instruct', 'qwen2.5:3b-instruct')
    require(len(original) == 100 and len(extra) == supplemental_count * 2, 'Incomplete original/supplemental coverage')
    require(len({row['sourceTree'] for row in original + extra}) == 1, 'Mixed source tree')
    require(all(r['datasetSha256'] == 'f8847449b992cbacda13810e0958eeb323f9f1f62355f5e5488ac4812783e1e8' for r in extra),
            'Independent frozen structure corpus changed')
    for row in original + extra:
        require(row['sourceCommit'] == head, 'Mixed source')
        require(all(a['modelDigest'] == MODEL_DIGESTS[row['model']] for a in row['attempts']),
                'Original model weights changed')
        check_row(row)
    result = {'measurementStatus': 'PASS', 'qualityThresholdEnforced': False, 'sourceCommit': head, 'models': {}}
    for model in models:
        first = [r for r in original if r['model'] == model]
        second = [r for r in extra if r['model'] == model]
        require(len(first) == len({r['caseId'] for r in first}) == 50, 'Duplicate/missing frozen case')
        require(len(second) == len({r['caseId'] for r in second}) == supplemental_count, 'Duplicate/missing unseen case')
        require(all(r['datasetSha256'] == '629798799640026f8ae7f096027bece765d3dda74386fbbceead0237be76a9a9' for r in first),
                'Original frozen corpus changed')
        result['models'][model] = {'original': summarize(first), 'supplemental': summarize(second)}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--head', required=True)
    parser.add_argument('--supplemental-count', type=int, default=6)
    args = parser.parse_args()
    result = review(args.root, args.head, args.supplemental_count)
    (args.root / 'structure-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
