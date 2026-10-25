from __future__ import annotations
from typing import Any, TypedDict
from ..contracts import SpringToolGateway, TaskDefinition
class State(TypedDict,total=False): request:dict[str,Any]; response:dict[str,Any]
class HousingSavingsCloseTask:
 definition=TaskDefinition("HOUSING_SAVINGS_CLOSE","청년 주택드림 청약통장 Mock 해지",("b02_prepare","b02_execute"))
 def __init__(self,gateway:SpringToolGateway): self.gateway=gateway
 def run(self,request:dict[str,Any])->dict[str,Any]:
  for key in ("requestId","customerId","accountId"):
   if request.get(key) in (None,""): raise ValueError(f"{key} is required")
  if request.get("submissionConfirmed"):
   if not (request.get("consented") and request.get("mockSignatureCompleted") and request.get("bindingId")): raise ValueError("consent, mock signature, and verification binding are required before execute")
   return self._graph("execute").invoke({"request":request})["response"]
  return self._graph("prepare").invoke({"request":request})["response"]
 def _graph(self,node:str):
  from langgraph.graph import START,END,StateGraph
  g=StateGraph(State); g.add_node(node,lambda s:{"response":getattr(self.gateway,"b02_"+node)(s["request"])});g.add_edge(START,node);g.add_edge(node,END);return g.compile()
