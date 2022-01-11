package scorex.testkit.properties

import akka.actor._
import akka.testkit.TestProbe
import org.ergoplatform.modifiers.BlockSection
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoSyncInfo}
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.{GetNodeViewChanges, ModifiersFromRemote}
import scorex.core.consensus.SyncInfo
import scorex.core.network.NetworkController.ReceivableMessages.{PenalizePeer, SendToNetwork}
import org.ergoplatform.network.ErgoNodeViewSynchronizer.ReceivableMessages._
import org.ergoplatform.nodeView.state.UtxoState.ManifestId
import org.ergoplatform.nodeView.state.{ErgoState, SnapshotsDb, SnapshotsInfo, UtxoStateReader}
import org.ergoplatform.settings.Algos
import org.ergoplatform.settings.Algos.HF
import scorex.core.network._
import scorex.core.network.message._
import scorex.core.network.peer.PenaltyType
import scorex.core.serialization.{BytesSerializable, ScorexSerializer}
import scorex.crypto.authds.avltree.batch.serialization.BatchAVLProverSerializer
import scorex.crypto.hash.Digest32
import scorex.testkit.generators.{SyntacticallyTargetedModifierProducer, TotallyValidModifierProducer}
import scorex.testkit.utils.AkkaFixture
import scorex.util.ScorexLogging
import scorex.util.serialization._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
trait NodeViewSynchronizerTests[ST <: ErgoState[ST]] extends AnyPropSpec
  with Matchers
  with ScorexLogging
  with SyntacticallyTargetedModifierProducer
  with TotallyValidModifierProducer[ST] {

  val historyGen: Gen[ErgoHistory]
  val memPool: ErgoMemPool

  val stateGen: Gen[ST]

  def nodeViewSynchronizer(implicit system: ActorSystem):
    (ActorRef, ErgoSyncInfo, BlockSection, ErgoTransaction, ConnectedPeer, TestProbe, TestProbe, TestProbe, TestProbe, ScorexSerializer[BlockSection])

  class SynchronizerFixture extends AkkaFixture {
    @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
    val (node, syncInfo, mod, tx, peer, pchProbe, ncProbe, vhProbe, eventListener, modSerializer) = nodeViewSynchronizer
  }

  // ToDo: factor this out of here and NVHTests?
  private def withFixture(testCode: SynchronizerFixture => Any): Unit = {
    val fixture = new SynchronizerFixture
    try {
      testCode(fixture)
    }
    finally {
      Await.result(fixture.system.terminate(), Duration.Inf)
    }
  }

  property("NodeViewSynchronizer: SuccessfulTransaction") {
    withFixture { ctx =>
      import ctx._
      node ! SuccessfulTransaction(tx)
      ncProbe.fishForMessage(3 seconds) { case m => m.isInstanceOf[SendToNetwork] }
    }
  }

  property("NodeViewSynchronizer: FailedTransaction") {
    withFixture { ctx =>
      import ctx._
      node ! FailedTransaction(tx.id, new Exception, immediateFailure = true)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  property("NodeViewSynchronizer: SyntacticallySuccessfulModifier") {
    withFixture { ctx =>
      import ctx._
      node ! SyntacticallySuccessfulModifier(mod)
      // todo ? : NVS currently does nothing in this case. Should it do?
    }
  }

  property("NodeViewSynchronizer: SyntacticallyFailedModification") {
    withFixture { ctx =>
      import ctx._
      node ! SyntacticallyFailedModification(mod, new Exception)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  property("NodeViewSynchronizer: SemanticallySuccessfulModifier") {
    withFixture { ctx =>
      import ctx._
      node ! SemanticallySuccessfulModifier(mod)
      ncProbe.fishForMessage(3 seconds) { case m => m.isInstanceOf[SendToNetwork] }
    }
  }

  property("NodeViewSynchronizer: SemanticallyFailedModification") {
    withFixture { ctx =>
      import ctx._
      node ! SemanticallyFailedModification(mod, new Exception)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  //TODO rewrite
  ignore("NodeViewSynchronizer: Message: SyncInfoSpec") {
    withFixture { ctx =>
      import ctx._

      val dummySyncInfoMessageSpec = new SyncInfoMessageSpec[SyncInfo](serializer = new ScorexSerializer[SyncInfo]{
        override def parse(r: Reader): SyncInfo = {
          throw new Exception()
        }

        override def serialize(obj: SyncInfo, w: Writer): Unit = {}
      })

      val dummySyncInfo: SyncInfo = new SyncInfo {
        type M = BytesSerializable

        def serializer: ScorexSerializer[M] = throw new Exception
      }

      val msgBytes = dummySyncInfoMessageSpec.toBytes(dummySyncInfo)

      node ! Message(dummySyncInfoMessageSpec, Left(msgBytes), Some(peer))
      //    vhProbe.fishForMessage(3 seconds) { case m => m == OtherNodeSyncingInfo(peer, dummySyncInfo) }
    }
  }

  property("NodeViewSynchronizer: Message: InvSpec") {
    withFixture { ctx =>
      import ctx._
      val spec = new InvSpec(3)
      val modifiers = Seq(mod.id)
      val msgBytes = spec.toBytes(InvData(mod.modifierTypeId, modifiers))
      node ! Message(spec, Left(msgBytes), Some(peer))
      pchProbe.fishForMessage(5 seconds) {
        case _: Message[_] => true
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message: RequestModifierSpec") {
    withFixture { ctx =>
      import ctx._
      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      val h = historyGen.sample.get
      val mod = syntacticallyValidModifier(h)
      val (newH, _) = h.append(mod).get
      val m = memPool
      val spec = new RequestModifierSpec(3)
      val modifiers = Seq(mod.id)
      val msgBytes = spec.toBytes(InvData(mod.modifierTypeId, modifiers))
      node ! ChangedHistory(newH)
      node ! ChangedMempool(m)
      node ! Message(spec, Left(msgBytes), Option(peer))

      pchProbe.fishForMessage(5 seconds) {
        case _: Message[_] => true
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message: Non-Asked Modifiers from Remote") {
    withFixture { ctx =>
      import ctx._

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val msgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(modifiersSpec, Left(msgBytes), Option(peer))
      val messages = vhProbe.receiveWhile(max = 3 seconds, idle = 1 second) { case m => m }
      assert(!messages.exists(_.isInstanceOf[ModifiersFromRemote]))
    }
  }

  property("NodeViewSynchronizer: Message: Asked Modifiers from Remote") {
    withFixture { ctx =>
      import ctx._
      vhProbe.expectMsgType[GetNodeViewChanges]

      val invSpec = new InvSpec(3)
      val invMsgBytes = invSpec.toBytes(InvData(mod.modifierTypeId, Seq(mod.id)))

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val modMsgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(invSpec, Left(invMsgBytes), Option(peer))
      node ! Message(modifiersSpec, Left(modMsgBytes), Option(peer))
      vhProbe.fishForMessage(3 seconds) {
        case m: ModifiersFromRemote => m.modifiers.toSeq.contains(mod)
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message - CheckDelivery -  Do not penalize if delivered") {
    withFixture { ctx =>
      import ctx._

      val invSpec = new InvSpec(3)
      val invMsgBytes = invSpec.toBytes(InvData(mod.modifierTypeId, Seq(mod.id)))

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val modMsgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(invSpec, Left(invMsgBytes), Option(peer))
      node ! Message(modifiersSpec, Left(modMsgBytes), Option(peer))
      system.scheduler.scheduleOnce(1 second, node, Message(modifiersSpec, Left(modMsgBytes), Option(peer)))
      val messages = ncProbe.receiveWhile(max = 5 seconds, idle = 1 second) { case m => m }
      assert(!messages.contains(PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.MisbehaviorPenalty)))
    }
  }

  property("NodeViewSynchronizer: ResponseFromLocal") {
    withFixture { ctx =>
      import ctx._
      node ! ResponseFromLocal(peer, mod.modifierTypeId, Seq(mod.id -> mod.bytes))
      pchProbe.expectMsgType[Message[_]]
    }
  }

  property("NodeViewSynchronizer: GetSnapshotInfo") {
    withFixture { ctx =>
      import ctx._

      val s = stateGen.sample.get

      if (s.isInstanceOf[UtxoStateReader]) {
        // To initialize utxoStateReaderOpt in ErgoNodeView Synchronizer
        node ! ChangedState(s)

        // First, store snapshots info in DB
        val m = (0 until 100).map { _ =>
          Random.nextInt(1000000) -> (Digest32 @@ Algos.decode(mod.id).get)
        }.toMap
        val si = SnapshotsInfo(m)
        val db = SnapshotsDb.create(s.constants.settings)
        db.writeSnapshotsInfo(si)

        // Then send message to request it
        node ! Message[Unit](GetSnapshotsInfoSpec, Left(Array.empty[Byte]), Option(peer))
        ncProbe.fishForMessage(5 seconds) {
          case stn: SendToNetwork if stn.message.spec.isInstanceOf[SnapshotsInfoSpec.type] => true
          case _: Any => false
        }
      } else {
        log.info("Snapshots not supported by digest-state")
      }
    }
  }

  property("NodeViewSynchronizer: GetManifest") {
    withFixture { ctx =>
      import ctx._

      val s = stateGen.sample.get

      s match {
        case usr: UtxoStateReader => {
          // To initialize utxoStateReaderOpt in ErgoNodeView Synchronizer
          node ! ChangedState(s)

          // Generate some snapshot
          val height = 1
          usr.applyModifier(mod, Some(height))
          usr.persistentProver.generateProofAndUpdateStorage()
          implicit val hf: HF = Algos.hash
          val serializer = new BatchAVLProverSerializer[Digest32, HF]
          val (manifest, subtrees) = serializer.slice(usr.persistentProver.avlProver, subtreeDepth = 12)

          val db = SnapshotsDb.create(s.constants.settings)
          db.writeSnapshot(height, manifest, subtrees)

          // Then send message to request it
          node ! Message[ManifestId](new GetManifestSpec, Left(manifest.id), Option(peer))
          ncProbe.fishForMessage(5 seconds) {
            case stn: SendToNetwork if stn.message.spec.isInstanceOf[ManifestSpec.type] => true
            case _: Any => false
          }
        }
        case _ =>
          log.info("Snapshots not supported by digest-state")
      }
    }
  }


  property("NodeViewSynchronizer: GetSnapshotChunk") {
    withFixture { ctx =>
      import ctx._

      val s = stateGen.sample.get

      s match {
        case usr: UtxoStateReader => {
          // To initialize utxoStateReaderOpt in ErgoNodeView Synchronizer
          node ! ChangedState(s)

          // Generate some snapshot
          val height = 1
          usr.applyModifier(mod, Some(height))
          usr.persistentProver.generateProofAndUpdateStorage()
          implicit val hf: HF = Algos.hash
          val serializer = new BatchAVLProverSerializer[Digest32, HF]
          val (manifest, subtrees) = serializer.slice(usr.persistentProver.avlProver, subtreeDepth = 12)

          val db = SnapshotsDb.create(s.constants.settings)
          db.writeSnapshot(height, manifest, subtrees)

          // Then send message to request it
          node ! Message[ManifestId](new GetUtxoSnapshotChunkSpec, Left(subtrees.last.id), Option(peer))
          ncProbe.fishForMessage(5 seconds) {
            case stn: SendToNetwork if stn.message.spec.isInstanceOf[UtxoSnapshotChunkSpec.type] => true
            case _: Any => false
          }
        }
        case _ =>
          log.info("Snapshots not supported by digest-state")
      }
    }
  }
}
