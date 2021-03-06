package brs.db.sql;

import brs.*;
import brs.db.BlockDb;
import brs.db.BurstIterator;
import brs.db.store.BlockchainStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public abstract class SqlBlockchainStore implements BlockchainStore {

  private final TransactionDb transactionDb = Burst.getDbs().getTransactionDb();
  private final BlockDb blockDb = Burst.getDbs().getBlockDb();


  @Override
  public BurstIterator<BlockImpl> getAllBlocks() {
    try (Connection con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block ORDER BY db_id ASC")) {
      return getBlocks(con, pstmt);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<BlockImpl> getBlocks(int from, int to) {
    try (Connection  con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE height <= ? AND height >= ? ORDER BY height DESC")) {
      int blockchainHeight = Burst.getBlockchain().getHeight();
      pstmt.setInt(1, blockchainHeight - Math.max(from, 0));
      pstmt.setInt(2, to > 0 ? blockchainHeight - to : 0);
      return getBlocks(con, pstmt);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }


  @Override
  public BurstIterator<BlockImpl> getBlocks(Account account, int timestamp, int from, int to) {
    try (Connection con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE generator_id = ? "
                                                        + (timestamp > 0 ? " AND timestamp >= ? " : " ") + "ORDER BY db_id DESC"
                                                        + DbUtils.limitsClause(from, to))) {
      int i = 0;
      pstmt.setLong(++i, account.getId());
      if (timestamp > 0) {
        pstmt.setInt(++i, timestamp);
      }
      DbUtils.setLimits(++i, pstmt, from, to);
      return getBlocks(con, pstmt);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<BlockImpl> getBlocks(Connection con, PreparedStatement pstmt) {
    return new DbIterator<>(con, pstmt, new DbIterator.ResultSetReader<BlockImpl>() {
        @Override
        public BlockImpl get(Connection con, ResultSet rs) throws BurstException.ValidationException {
          return blockDb.loadBlock(con, rs);
        }
      });
  }

  @Override
  public List<Long> getBlockIdsAfter(long blockId, int limit) {
    if (limit > 1440) {
      throw new IllegalArgumentException("Can't get more than 1440 blocks at a time");
    }
    try (Connection con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT id FROM block WHERE db_id > (SELECT db_id FROM block WHERE id = ?) ORDER BY db_id ASC" + DbUtils.limitsClause(limit) )) {
      List<Long> result = new ArrayList<>();
      pstmt.setLong(1, blockId);
      DbUtils.setLimits(2, pstmt, limit);
      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getLong("id"));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public List<BlockImpl> getBlocksAfter(long blockId, int limit) {
    if (limit > 1440) {
      throw new IllegalArgumentException("Can't get more than 1440 blocks at a time");
    }
    try (Connection con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE db_id > (SELECT db_id FROM block WHERE id = ?) ORDER BY db_id ASC" + DbUtils.limitsClause(limit) )) {
      List<BlockImpl> result = new ArrayList<>();
      pstmt.setLong(1, blockId);
      DbUtils.setLimits(2, pstmt, limit);
      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          result.add(blockDb.loadBlock(con, rs));
        }
      }
      return result;
    } catch (BurstException.ValidationException | SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }


  @Override
  public int getTransactionCount() {
    try (Connection con = Db.getConnection(); PreparedStatement pstmt = con.prepareStatement("SELECT COUNT(*) FROM transaction");
         ResultSet rs = pstmt.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<TransactionImpl> getAllTransactions() {
    try (Connection con = Db.getConnection();
         PreparedStatement pstmt = con.prepareStatement("SELECT * FROM transaction ORDER BY db_id ASC")) {
      return getTransactions(con, pstmt);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }


  @Override
  public BurstIterator<TransactionImpl> getTransactions(Account account, int numberOfConfirmations, byte type, byte subtype,
                                                        int blockTimestamp, int from, int to) {
    int height = numberOfConfirmations > 0 ? Burst.getBlockchain().getHeight() - numberOfConfirmations : Integer.MAX_VALUE;
    if (height < 0) {
      throw new IllegalArgumentException("Number of confirmations required " + numberOfConfirmations
                                         + " exceeds current blockchain height " + Burst.getBlockchain().getHeight());
    }
    try (Connection con = Db.getConnection()) {
      StringBuilder buf = new StringBuilder();
      buf.append("SELECT * FROM transaction WHERE recipient_id = ? AND sender_id <> ? ");
      if (blockTimestamp > 0) {
        buf.append("AND block_timestamp >= ? ");
      }
      if (type >= 0) {
        buf.append("AND type = ? ");
        if (subtype >= 0) {
          buf.append("AND subtype = ? ");
        }
      }
      if (height < Integer.MAX_VALUE) {
        buf.append("AND height <= ? ");
      }
      buf.append("UNION ALL SELECT * FROM transaction WHERE sender_id = ? ");
      if (blockTimestamp > 0) {
        buf.append("AND block_timestamp >= ? ");
      }
      if (type >= 0) {
        buf.append("AND type = ? ");
        if (subtype >= 0) {
          buf.append("AND subtype = ? ");
        }
      }
      if (height < Integer.MAX_VALUE) {
        buf.append("AND height <= ? ");
      }
      buf.append("ORDER BY block_timestamp DESC, id DESC");
      buf.append(DbUtils.limitsClause(from, to));

      int i = 0;
      try (PreparedStatement pstmt = con.prepareStatement(buf.toString())) {
        pstmt.setLong(++i, account.getId());
        pstmt.setLong(++i, account.getId());
        if (blockTimestamp > 0) {
          pstmt.setInt(++i, blockTimestamp);
        }
        if (type >= 0) {
          pstmt.setByte(++i, type);
          if (subtype >= 0) {
            pstmt.setByte(++i, subtype);
          }
        }
        if (height < Integer.MAX_VALUE) {
          pstmt.setInt(++i, height);
        }
        pstmt.setLong(++i, account.getId());
        if (blockTimestamp > 0) {
          pstmt.setInt(++i, blockTimestamp);
        }
        if (type >= 0) {
          pstmt.setByte(++i, type);
          if (subtype >= 0) {
            pstmt.setByte(++i, subtype);
          }
        }
        if (height < Integer.MAX_VALUE) {
          pstmt.setInt(++i, height);
        }
        DbUtils.setLimits(++i, pstmt, from, to);
        return getTransactions(con, pstmt);
      }
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<TransactionImpl> getTransactions(Connection con, PreparedStatement pstmt) {
    return new DbIterator<>(con, pstmt, new DbIterator.ResultSetReader<TransactionImpl>() {
        @Override
        public TransactionImpl get(Connection con, ResultSet rs) throws BurstException.ValidationException {
          return transactionDb.loadTransaction(con, rs);
        }
      });
  }

  @Override
  public boolean addBlock(BlockImpl block) {
    try (Connection con = Db.getConnection()) {
      blockDb.saveBlock(con, block);
      return true;
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  public void scan(int height)
  {
  }
}
