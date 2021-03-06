package com.shirey.cafe.dao;

import com.shirey.cafe.db.ConnectionPool;
import com.shirey.cafe.db.ProxyConnection;
import com.shirey.cafe.entity.Dish;
import com.shirey.cafe.entity.Order;
import com.shirey.cafe.entity.PaymentType;
import com.shirey.cafe.exception.ConnectionException;
import com.shirey.cafe.exception.DAOException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code OrderDAO} class
 * provides access to the table 'order' in the database
 *
 * @author Alex Shirey
 */

public class OrderDAO extends AbstractDAO<Integer, Order> {

    private static final String SQL_INSERT_NEW_ORDER =
            "INSERT INTO `order`(user_id, payment_type, pick_up_time, order_price, is_paid) VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_UPDATE_USER_BALANCE_AND_LOYALTY_POINTS =
            "UPDATE `user` SET balance=?, loyalty_points=? WHERE user_id=?;";

    private static final String SQL_INSERT_ORDER_HAS_DISH =
            "INSERT INTO `order_has_dish`(order_id, dish_id, dish_price, dish_quantity) VALUES (?, ?, ?, ?)";

    private static final String SQL_UPDATE_USER_LOYALTY_POINTS =
            "UPDATE `user` SET loyalty_points=? WHERE user_id=?;";

    private static final String SQL_UPDATE_ORDER_STATUS =
            "UPDATE `order` SET status=? WHERE order_id=?";

    private static final String SQL_SELECT_ORDER_BY_ID =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order` WHERE order_id=?;";

    private static final String SQL_SELECT_All_ORDERS =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order`";

    private static final String SQL_SELECT_ORDERS_BY_USER_ID =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order` WHERE user_id=?;";

    private static final String SQL_SELECT_ACTIVE_ORDERS_BY_USER_ID =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order` WHERE user_id=? AND `status`='ACTIVE'";

    private static final String SQL_SELECT_CANCELLED_ORDERS_BY_USER_ID =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order` WHERE user_id=? AND `status`='CANCELLED'";

    private static final String SQL_SELECT_ORDERS_WITH_REVIEW =
            "SELECT order_id, user_id, payment_type, pick_up_time, order_price, is_paid, status, create_date, rating, review FROM `order` WHERE review IS NOT NULL";

    private static final String SQL_UPDATE_ORDER_RATING_AND_REVIEW =
            "UPDATE `order` SET rating=?, review=? WHERE order_id=?";

    private static final String SQL_UPDATE_ORDER_IS_PAID_AND_STATUS =
            "UPDATE `order` SET is_paid=?, status=? WHERE order_id=?";


    /**
     * Updates the table 'user' with new balance and loyaltyPoints values,
     * inserts in the table 'order' a new row that represents {@code Order} object,
     * sets the auto generated id to this {@code Order} object,
     * inserts in the table 'order_has_dish' a new row that represents {@code Map} user cart.
     *
     * @param balance       a user balance value to update
     * @param loyaltyPoints a user loyalty points value to update
     * @param order         a {@code Order} object to insert into the table 'order'
     * @param cart          a map which values are used to insert into the table 'order_has_dish'
     * @throws DAOException if a database access error occurs or
     *                      if now rows where updated
     */
    public void create(BigDecimal balance, BigDecimal loyaltyPoints, Order order, Map<Dish, Integer> cart) throws DAOException {

        ProxyConnection connection;
        try {
            connection = ConnectionPool.getInstance().getConnection();
        } catch (ConnectionException e) {
            throw new DAOException(e);
        }

        try (PreparedStatement updateUser = connection.prepareStatement(SQL_UPDATE_USER_BALANCE_AND_LOYALTY_POINTS);
             PreparedStatement updateOrder = connection.prepareStatement(SQL_INSERT_NEW_ORDER, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement updateOrderHasDish = connection.prepareStatement(SQL_INSERT_ORDER_HAS_DISH)) {

            connection.setAutoCommit(false);

            updateUser.setBigDecimal(1, balance);
            updateUser.setBigDecimal(2, loyaltyPoints);
            updateUser.setInt(3, order.getUserId());

            updateOrder.setInt(1, order.getUserId());
            updateOrder.setString(2, order.getPaymentType().name());
            updateOrder.setTimestamp(3, new Timestamp(order.getPickUpTime().getTime()));
            updateOrder.setBigDecimal(4, order.getOrderPrice());
            updateOrder.setBoolean(5, order.isPaid());

            if (updateUser.executeUpdate() == 0 || updateOrder.executeUpdate() == 0) {
                throw new DAOException("Creating order failed (updating user and order tables), no rows affected.");
            }

            ResultSet generatedKeys = updateOrder.getGeneratedKeys();
            if (generatedKeys.next()) {
                order.setOrderId(generatedKeys.getInt(1));
            } else {
                throw new DAOException("no auto-generated keys.");
            }

            Set<Map.Entry<Dish, Integer>> entries = cart.entrySet();
            for (Map.Entry<Dish, Integer> entry : entries) {
                updateOrderHasDish.setInt(1, order.getOrderId());
                updateOrderHasDish.setInt(2, entry.getKey().getDishId());
                updateOrderHasDish.setBigDecimal(3, entry.getKey().getPrice());
                updateOrderHasDish.setInt(4, entry.getValue());
                if (updateOrderHasDish.executeUpdate() == 0) {
                    throw new DAOException("Inserting data to table order_has_dish failed (updating user and order tables), no rows affected.");
                }
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException e1) {
                throw new DAOException("SQL exception, rollback wasn't made", e);
            }
            throw new DAOException("SQL exception (query or table failed)", e);
        } finally {
            returnConnection(connection);
        }
    }

    /**
     * Updates the table 'user' with new loyaltyPoints value,
     * updates the table 'order' with new order status value.
     *
     * @param loyaltyPoints a user loyalty points value to update
     * @param order         a {@code Order} object to get order id and user id values
     * @param status        a order status value to update
     * @throws DAOException if a database access error occurs or
     *                      if now rows where updated
     */
    public void cancelOrder(BigDecimal loyaltyPoints, Order order, Order.Status status) throws DAOException {

        ProxyConnection connection;
        try {
            connection = ConnectionPool.getInstance().getConnection();
        } catch (ConnectionException e) {
            throw new DAOException(e);
        }

        try (PreparedStatement updateUser = connection.prepareStatement(SQL_UPDATE_USER_LOYALTY_POINTS);
             PreparedStatement updateOrder = connection.prepareStatement(SQL_UPDATE_ORDER_STATUS)) {

            connection.setAutoCommit(false);

            updateUser.setBigDecimal(1, loyaltyPoints);
            updateUser.setInt(2, order.getUserId());

            updateOrder.setString(1, status.name());
            updateOrder.setInt(2, order.getOrderId());

            if (updateUser.executeUpdate() == 0 || updateOrder.executeUpdate() == 0) {
                throw new DAOException("Cancelling order failed (updating user and order tables), no rows affected.");
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException e1) {
                throw new DAOException("SQL exception, rollback wasn't made", e);
            }
            throw new DAOException("SQL exception (query or table failed)", e);
        } finally {
            returnConnection(connection);
        }
    }

    /**
     * Gets a row from the table using order id,
     * builds and returns {@code Order} object that represents this id
     *
     * @param id a order id
     * @return a {@code Order}, or null if no order id is founded in the table
     * @throws DAOException if a database access error occurs
     */
    @Override
    public Order findEntityById(Integer id) throws DAOException {

        Order order = null;
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SELECT_ORDER_BY_ID)) {
            preparedStatement.setInt(1, id);
            ResultSet rs = preparedStatement.executeQuery();
            if (rs.next()) {
                order = buildOrder(rs);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return order;
    }

    /**
     * Gets all rows from the table 'order' and
     * returns them as a list of {@code Order} objects in reverse order
     *
     * @return a list contains {@code Order}, not null
     * @throws DAOException if a database access error occurs
     */
    @Override
    public List<Order> findAll() throws DAOException {

        LinkedList<Order> orders = new LinkedList<>();
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             Statement statement = connection.createStatement()) {
            ResultSet resultSet = statement.executeQuery(SQL_SELECT_All_ORDERS);
            while (resultSet.next()) {
                Order order = buildOrder(resultSet);
                orders.addFirst(order);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return orders;
    }

    /**
     * Gets all rows from the table 'order' for one user (using user id),
     * returns them as a list of {@code Order} objects in reverse order
     *
     * @param userId a user id
     * @return a list contains {@code Order}, not null
     * @throws DAOException if a database access error occurs
     */
    public List<Order> findOrdersByUserId(int userId) throws DAOException {

        LinkedList<Order> orders = new LinkedList<>();
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SELECT_ORDERS_BY_USER_ID)) {
            preparedStatement.setInt(1, userId);
            ResultSet rs = preparedStatement.executeQuery();
            while (rs.next()) {
                Order order = buildOrder(rs);
                orders.addFirst(order);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return orders;
    }

    /**
     * Gets all rows from the table 'order' for one user (using user id) where order status is ACTIVE,
     * returns them as a list of {@code Order} objects in reverse order
     *
     * @param userId a user id
     * @return a list contains {@code Order}, not null
     * @throws DAOException if a database access error occurs
     */
    public List<Order> findActiveOrdersByUserId(int userId) throws DAOException {

        LinkedList<Order> orders = new LinkedList<>();
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SELECT_ACTIVE_ORDERS_BY_USER_ID)) {
            preparedStatement.setInt(1, userId);
            ResultSet rs = preparedStatement.executeQuery();
            while (rs.next()) {
                Order order = buildOrder(rs);
                orders.addFirst(order);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return orders;
    }

    /**
     * Gets all rows from the table 'order' for one user (using user id) where order status is CANCELLED,
     * returns them as a list of {@code Order} objects in reverse order
     *
     * @param userId a user id
     * @return a list contains {@code Order}, not null
     * @throws DAOException if a database access error occurs
     */
    public List<Order> findCancelledOrdersByUserId(int userId) throws DAOException {

        LinkedList<Order> orders = new LinkedList<>();
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SELECT_CANCELLED_ORDERS_BY_USER_ID)) {
            preparedStatement.setInt(1, userId);
            ResultSet rs = preparedStatement.executeQuery();
            while (rs.next()) {
                Order order = buildOrder(rs);
                orders.addFirst(order);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return orders;
    }

    /**
     * Gets all rows from the table 'order' where review is not null,
     * returns them as a list of {@code Order} objects in reverse order
     *
     * @return a list contains {@code Order} with reviews, not null
     * @throws DAOException if a database access error occurs
     */
    public List<Order> findOrdersWithReview() throws DAOException {

        LinkedList<Order> orders = new LinkedList<>();
        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SELECT_ORDERS_WITH_REVIEW)) {
            ResultSet rs = preparedStatement.executeQuery();
            while (rs.next()) {
                Order order = buildOrder(rs);
                orders.addFirst(order);
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
        return orders;
    }

    /**
     * Updates a row in the table using order id
     * with new rating and review values
     *
     * @param orderId a order id
     * @param rating  a new rating value
     * @param review  a new review value
     * @throws DAOException if {@code DaoException} occurs (database access error) or
     *                      if now rows where updated
     */
    public void updateOrderReview(int orderId, Integer rating, String review) throws DAOException {

        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement updateOrderReview = connection.prepareStatement(SQL_UPDATE_ORDER_RATING_AND_REVIEW)) {
            if (rating != null) {
                updateOrderReview.setInt(1, rating);
            } else {
                updateOrderReview.setNull(1, 0);
            }
            updateOrderReview.setString(2, review);
            updateOrderReview.setInt(3, orderId);
            if (updateOrderReview.executeUpdate() == 0) {
                throw new DAOException("Updating order failed (updating user and order tables), no rows affected.");
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
    }

    /**
     * Updates a row in the table using order id
     * with new isPaid and status values
     *
     * @param orderId a order id
     * @param isPaid  a new isPaid value
     * @param status  a new status value
     * @throws DAOException if {@code DaoException} occurs (database access error) or
     *                      if now rows where updated
     */
    public void updateOrder(int orderId, boolean isPaid, Order.Status status) throws DAOException {

        try (ProxyConnection connection = ConnectionPool.getInstance().getConnection();
             PreparedStatement updateOrder = connection.prepareStatement(SQL_UPDATE_ORDER_IS_PAID_AND_STATUS)) {
            updateOrder.setBoolean(1, isPaid);
            updateOrder.setString(2, status.name());
            updateOrder.setInt(3, orderId);
            if (updateOrder.executeUpdate() == 0) {
                throw new DAOException("Updating order failed (updating user and order tables), no rows affected.");
            }
        } catch (ConnectionException e) {
            throw new DAOException(e);
        } catch (SQLException e) {
            throw new DAOException("SQL exception (query or table failed)", e);
        }
    }

    /**
     * Creates a new {@code Order} object and
     * sets its values using {@code ResultSet}
     *
     * @param rs a {@code ResultSet} to build an object
     * @return a {@code Order}
     */
    private Order buildOrder(ResultSet rs) throws SQLException {

        Order order = new Order();
        order.setOrderId(rs.getInt("order_id"));
        order.setUserId(rs.getInt("user_id"));
        order.setPaymentType(PaymentType.valueOf(rs.getString("payment_type")));
        order.setPickUpTime(rs.getTimestamp("pick_up_time"));
        order.setOrderPrice(rs.getBigDecimal("order_price"));
        order.setPaid(rs.getBoolean("is_paid"));
        order.setStatus(Order.Status.valueOf(rs.getString("status")));
        order.setCreateDate(rs.getTimestamp("create_date"));
        order.setRating(rs.getInt("rating"));
        order.setReview(rs.getString("review"));

        return order;
    }
}
