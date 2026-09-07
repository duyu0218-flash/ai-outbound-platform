#!/usr/bin/env python3
"""Check an operator-supplied, expanded FreeSWITCH core XML. No PBX changes."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def assess(path, *, calls=200, legs_per_call=2, spare_sessions=100, cps=10):
    root=ET.parse(path).getroot()
    values={}
    nodes = root.findall('.//param') if root.tag == 'configuration' and root.get('name') == 'switch.conf' else root.findall('.//configuration[@name="switch.conf"]//param')
    for node in nodes:
        name=node.get('name')
        if name in ('max-sessions','sessions-per-second','rtp-start-port','rtp-end-port'):
            value=node.get('value','')
            if name in values:raise ValueError('duplicate core parameter: '+name)
            values[name]=int(value)  # unresolved variables fail closed
    needed=calls*legs_per_call+spare_sessions
    errors=[]
    if values.get('max-sessions',0)<needed:errors.append(f'max-sessions must be at least {needed}')
    if values.get('sessions-per-second',0)<cps*legs_per_call:
        errors.append(f'sessions-per-second must be at least {cps*legs_per_call}')
    start,end=values.get('rtp-start-port',0),values.get('rtp-end-port',0)
    if not 1024<=start<end<=65535 or end-start+1<needed*2:
        errors.append(f'RTP range must reserve at least {needed*2} UDP ports for the stated leg budget')
    return {'static_config_passed':not errors,'target_customer_calls':calls,'required_pbx_sessions':needed,
        'configured':values,'blockers':errors,'runtime_and_audio_verified':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expanded-core-xml',type=Path,required=True)
    parser.add_argument('--calls',type=int,default=200)
    parser.add_argument('--legs-per-call',type=int,default=2)
    parser.add_argument('--spare-sessions',type=int,default=100)
    parser.add_argument('--cps',type=int,default=10)
    args=parser.parse_args()
    if min(args.calls,args.legs_per_call,args.cps)<1 or args.spare_sessions<0:parser.error('invalid workload')
    try:
        result=assess(args.expanded_core_xml,calls=args.calls,legs_per_call=args.legs_per_call,
                      spare_sessions=args.spare_sessions,cps=args.cps)
    except (OSError,ValueError,ET.ParseError) as exc:
        result={'static_config_passed':False,'blockers':[str(exc)],'runtime_and_audio_verified':False}
    print(json.dumps(result,ensure_ascii=False,indent=2))
    raise SystemExit(0 if result['static_config_passed'] else 1)
