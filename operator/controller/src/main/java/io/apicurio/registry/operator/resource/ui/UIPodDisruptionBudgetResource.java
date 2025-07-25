package io.apicurio.registry.operator.resource.ui;

import io.apicurio.registry.operator.api.v1.ApicurioRegistry3;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import static io.apicurio.registry.operator.resource.ResourceKey.UI_POD_DISRUPTION_BUDGET_KEY;

@KubernetesDependent
public class UIPodDisruptionBudgetResource
        extends CRUDKubernetesDependentResource<PodDisruptionBudget, ApicurioRegistry3> {

    public UIPodDisruptionBudgetResource() {
        super(PodDisruptionBudget.class);
    }

    @Override
    protected PodDisruptionBudget desired(ApicurioRegistry3 primary, Context<ApicurioRegistry3> context) {
        PodDisruptionBudget pdb = UI_POD_DISRUPTION_BUDGET_KEY.getFactory().apply(primary);
        return pdb;
    }
}
