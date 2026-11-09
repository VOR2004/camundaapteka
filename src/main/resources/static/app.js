async function loadOrders() {
  const message = document.querySelector('#message');
  try {
    const response = await fetch('/orders');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const orders = await response.json();
    const rows = orders.map(order => {
      const row = document.createElement('tr');
      for (const value of [order.orderId, order.status, order.updatedAt]) {
        const cell = document.createElement('td');
        cell.textContent = value;
        row.append(cell);
      }
      return row;
    });
    document.querySelector('#orders').replaceChildren(...rows);
    message.textContent = '';
  } catch (error) {
    message.textContent = `Не удалось загрузить заказы: ${error.message}. Обновите страницу.`;
  }
}
loadOrders();
