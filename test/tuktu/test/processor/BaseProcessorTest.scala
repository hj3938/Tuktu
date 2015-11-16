package tuktu.test.processor

import tuktu.api.DataPacket
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import tuktu.test.testUtil
import play.api.libs.json.JsObject
import tuktu.api.BaseProcessor

/**
 * Base class to test a processor
 */
class BaseProcessorTest {
    def apply(processor: BaseProcessor, config: JsObject, input: List[DataPacket],
            expectedList: List[DataPacket], timeout: Int = 5): Boolean = {
        // Initialize
        processor.initialize(config)
        
        // Build the required enumerator and iteratee and run the enumeratee
        val iter: Iteratee[DataPacket, List[DataPacket]] = Iteratee.fold(List[DataPacket]())(_ ++ List(_))
        val obtainedList = Await.result(Enumerator.enumerate(input).run(processor.processor() &>> iter), timeout seconds)
        
        // Compare/inspect the output
        obtainedList.zip(expectedList).forall(packets => {
            val obtained = packets._1
            val expected = packets._2
            
            // Inspect the data inside the packets
            obtained.data.zip(expected.data).forall(data => testUtil.inspectMaps(data._1, data._2))
        })
    }
}