package brs.http;

import brs.Block;
import brs.EconomicClustering;
import brs.Burst;
import brs.BurstException;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

public final class GetECBlock extends APIServlet.APIRequestHandler {

  static final GetECBlock instance = new GetECBlock();

  private GetECBlock() {
    super(new APITag[] {APITag.BLOCKS}, "timestamp");
  }

  @Override
  JSONStreamAware processRequest(HttpServletRequest req) throws BurstException {
    int timestamp = ParameterParser.getTimestamp(req);
    if (timestamp == 0) {
      timestamp = Burst.getEpochTime();
    }
    if (timestamp < Burst.getBlockchain().getLastBlock().getTimestamp() - 15) {
      return JSONResponses.INCORRECT_TIMESTAMP;
    }
    Block ecBlock = EconomicClustering.getECBlock(timestamp);
    JSONObject response = new JSONObject();
    response.put("ecBlockId", ecBlock.getStringId());
    response.put("ecBlockHeight", ecBlock.getHeight());
    response.put("timestamp", timestamp);
    return response;
  }

}
