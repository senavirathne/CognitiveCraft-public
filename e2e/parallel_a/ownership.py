"""Fail closed on edits outside the A00–A05 owned source boundary."""
import subprocess

base='ea7de8000b2b484b885576c34ed5358274936ce9'
allowed={
    '.github/workflows/parallel-a-runtime.yml',
    'core/src/main/java/dev/aivillages/core/kernel/Jobs.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobLifecycleStore.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobCodec.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobJournal.java',
    'core/src/test/java/dev/aivillages/core/kernel/JobJournalTest.java',
    'core/src/main/java/dev/aivillages/core/kernel/BootstrapController.java',
    'core/src/main/java/dev/aivillages/core/kernel/WorkerDispatcher.java',
    'core/src/main/java/dev/aivillages/core/kernel/SurvivalGateway.java',
    'fabric/src/main/java/dev/aivillages/fabric/KernelSession.java',
    'fabric/src/main/java/dev/aivillages/fabric/FabricGatewayWorld.java',
    'fabric/src/main/java/dev/aivillages/fabric/KernelCommands.java',
    'core/src/test/java/dev/aivillages/core/kernel/RecoveredJobOwnersTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/DepositGatewayTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/KnowledgeAccessAcknowledgedSourceTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/ArchiveHistoryReaderTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/ArchiveWriterTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/ArchiveServiceTest.java',
    'core/src/main/java/dev/aivillages/core/kernel/WorldRetentionManager.java',
    'core/src/main/java/dev/aivillages/core/kernel/ResourceLeaseService.java',
    'core/src/main/java/dev/aivillages/core/kernel/LeaseArchive.java',
    'core/src/test/java/dev/aivillages/core/kernel/LeaseArchiveTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/JobExecutorIntegrationTest.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobArchive.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobHistory.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobHistoryCodec.java',
    'core/src/main/java/dev/aivillages/core/kernel/JobOutcomeSource.java',
    'core/src/main/java/dev/aivillages/core/kernel/KnowledgeAccessSource.java',
    'core/src/test/java/dev/aivillages/core/kernel/JobArchiveTest.java',
    'core/src/test/java/dev/aivillages/core/kernel/ArchiveRequirementTest.java',
    'fabric/src/main/resources/ai-villages.mixins.json',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositVillagerParticipantMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositChestParticipantMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositInventoryMutationMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositChestRemovalMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositStackMutationMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositChestStorageLoadMixin.java',
    'fabric/src/main/java/dev/aivillages/fabric/mixin/DepositEntityStorageLoadMixin.java',
}
prefixes=('e2e/parallel_a/','core/src/main/java/dev/aivillages/core/kernel/runtime/',
          'core/src/test/java/dev/aivillages/core/kernel/runtime/evidence/')
prefixes+=('fabric/src/main/java/dev/aivillages/fabric/runtime/evidence/',
           'fabric/src/test/java/dev/aivillages/fabric/runtime/evidence/')
changed=sorted(set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
               |set(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],text=True).splitlines()))
bad=[p for p in changed if p not in allowed and not p.startswith(prefixes)]
if bad:raise SystemExit('Not owned by current A stage: '+repr(bad))
print('PARALLEL_A_OWNERSHIP_PASS '+str(len(changed))+' owned files; no B-owned edits')
