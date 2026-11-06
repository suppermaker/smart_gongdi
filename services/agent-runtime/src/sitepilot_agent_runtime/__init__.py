"""SitePilot agent runtime with lazy Temporal workflow loading."""

from sitepilot_agent_runtime.contracts import InvestigationInput, InvestigationResult

__all__ = ["InvestigationInput", "InvestigationResult", "InvestigationWorkflow"]


def __getattr__(name: str):
    if name == "InvestigationWorkflow":
        from sitepilot_agent_runtime.workflow import InvestigationWorkflow

        return InvestigationWorkflow
    raise AttributeError(name)
