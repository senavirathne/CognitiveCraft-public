"""Exact public fixture alias meanings. No native identity, grant or runtime authority."""
from __future__ import annotations
import math

SOURCES=((74,208,69),(70,208,69),(78,208,69))
DESTINATIONS=((77,208,72),(80,208,74),(70,208,74))
POSES={'near':(72.5,208,68.5),'offset':(65.5,208,68.5),'around-obstacle':(66.5,208,72.5)}
MATERIALS={'minecraft:oak_log':'minecraft:oak_planks','minecraft:birch_wood':'minecraft:birch_planks',
           'minecraft:stripped_birch_log':'minecraft:birch_planks'}


def resolve(binding):
    required={'material','quantity','actor','sourceBinding','destinationBinding','geometry','dimension'}
    if set(binding)!=required or binding['material'] not in MATERIALS or binding['dimension']!='minecraft:overworld':
        raise ValueError('Unsupported explicit held-out fixture binding')
    if type(binding['quantity']) is not int or binding['quantity'] not in (1,3,4,7,11):raise ValueError('Unfrozen quantity')
    for name,limit in (('actor',2),('sourceBinding',3),('destinationBinding',3)):
        if type(binding[name]) is not int or not 0<=binding[name]<limit:raise ValueError('Unfrozen native fixture alias')
    if binding['geometry'] not in POSES:raise ValueError('Unsupported geometry')
    def ref(p):return dict(dimension=binding['dimension'],x=p[0],y=p[1],z=p[2])
    output=MATERIALS[binding['material']]
    return {'player':'PlayerA' if binding['actor']==0 else 'PlayerB',
        'actor':'publicly enrolled ordinary citizen belonging to the selected player; resolve fresh UUIDs at bootstrap',
        'actorPose':list(POSES[binding['geometry']]),'source':ref(SOURCES[binding['sourceBinding']]),
        'destination':ref(DESTINATIONS[binding['destinationBinding']]),
        'setupBlocks':[{'position':[69,y,70],'block':'minecraft:stone'} for y in (208,209)] if binding['geometry']=='around-obstacle' else [],
        'fixtureFloor':{'min':[62,207,62],'max':[85,207,80],'block':'minecraft:stone'},
        'startingSource':{binding['material']:math.ceil(binding['quantity']/4)},
        'goal':{'capability':{'name':'cognitivecraft:produce_'+('oak_planks' if output=='minecraft:oak_planks' else 'birch_planks'),'version':2},
                'outputItem':output,'quantity':binding['quantity']},
        'setupRequires':'actual closed/certain owners before administrative fixture setup',
        'nativeIdentityAndAuthority':'supplied only by current A native adapter, never this dictionary',
        'routeStatus':'INTEGRATION_PENDING_DISABLED_ON_B'}
