package io.particle.mesh.setup.flow.setupsteps

import io.particle.mesh.common.android.livedata.nonNull
import io.particle.mesh.common.android.livedata.runBlockOnUiThreadAndAwaitUpdate
import io.particle.mesh.setup.flow.FlowIntent
import io.particle.mesh.setup.flow.MeshSetupStep
import io.particle.mesh.setup.flow.Scopes
import io.particle.mesh.setup.flow.context.SetupContexts
import io.particle.mesh.setup.flow.FlowUiDelegate


// FIXME: this whole class shouldn't even exist.
// FlowType should start by providing the FlowManager a barcode and
// the intended flow type (i.e.: setup, or one of the control panel actions)
class StepShowGetReadyForSetup(private val flowUi: FlowUiDelegate) : MeshSetupStep() {

    override suspend fun doRunStep(ctxs: SetupContexts, scopes: Scopes) {
        if (ctxs.flowIntent != FlowIntent.FIRST_TIME_SETUP
            || ctxs.getReadyNextButtonClickedLD.value == true) {
            return
        }

        ctxs.getReadyNextButtonClickedLD.nonNull(scopes).runBlockOnUiThreadAndAwaitUpdate(scopes) {
            flowUi.showGetReadyForSetupScreen()
        }
    }
}