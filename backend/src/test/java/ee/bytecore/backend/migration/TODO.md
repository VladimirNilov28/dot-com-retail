## Разбивка по файлам

Не пихай всё в `FlywayMigrationTest` — он должен остаться маленьким smoke-тестом ("миграции вообще применяются"). Дальше разделяй по **предметной области**, а не по "уровням" из плана GPT — так тесты легче найти, когда что-то ломается, и они естественно растут вместе с фичами:

```
FlywayMigrationTest        — smoke-тест применения миграций (уже есть)
UserSchemaTest             — constraints и поведение users/user_address/user_payment_methods
ProductSchemaTest          — products/product_variants
CartSchemaTest             — carts/cart_items (в т.ч. multi-cart поведение)
OrderSchemaTest             — orders/order_items/payment_details
InventorySchemaTest        — warehouses/inventory (unique pair, multi-warehouse)
WishlistSchemaTest         — wishlists/wishlist_items (unique per user)
TriggerBehaviorTest        — updated_at на разных таблицах (можно параметризовать одним тестом на все таблицы с триггером, не плодить копипасту)
```

Каждый — наследник твоего `AbstractIntegrationTest`, так что контейнер один на весь прогон, не 8 отдельных стартов Postgres.

## Улучшенный план задач

Убрал уровни 1-4 как отдельные пункты (наличие таблиц/колонок/PK) — они покрываются самим `FlywayMigrationTest`: если чего-то из этого нет, миграция не применится, приложение не стартует. Оставил только то, что реально ловит регрессии.

**Этап 0 — уже сделано**
- [x] `FlywayMigrationTest`: миграции применяются, `users` существует, enum `user_role` содержит правильные значения (это единственная структурная проверка, которая оправдана — enum'ы Postgres не ловятся автоматически при обычном старте так же надёжно)

**Этап 1 — constraints (быстрые, через попытку вставки + assertThrows)**
- [ ] `users.email` — NOT NULL (вставка без email кидает исключение)
- [ ] `users.email` — UNIQUE (вставка дубликата кидает исключение)
- [ ] `cart_items.quantity` — CHECK > 0 (вставка с quantity=0 кидает исключение)
- [ ] `inventory` — уникальная пара (product_variant_id, warehouse_id) (вставка дубликата пары кидает исключение)
- [ ] `wishlists.user_id` — UNIQUE (второй wishlist для того же юзера кидает исключение)

**Этап 2 — связи / каскады (тоже через реальные операции, не через information_schema)**
- [ ] Вставка `cart_items` с несуществующим `product_variant_id` — кидает FK-исключение
- [ ] Удаление `users` каскадно удаляет `user_address` (ON DELETE CASCADE)
- [ ] Удаление `carts` каскадно удаляет `cart_items`
- [ ] Удаление `orders` каскадно удаляет `order_items`
- [ ] Удаление `warehouses` каскадно удаляет `inventory`

**Этап 3 — специфичное бизнес-поведение (то, ради чего вы правили схему)**
- [ ] Один юзер может иметь несколько `carts` (никакого UNIQUE-конфликта при второй вставке) — это прямая регрессионная защита той правки, что мы обсуждали
- [ ] `orders.cart_id` можно оставить `NULL` (раз убрали NOT NULL/UNIQUE)
- [ ] Удаление `carts`, на которую ссылается `orders.cart_id`, не ломает `orders` (ON DELETE SET NULL) — проверить, что заказ остаётся, а `cart_id` становится NULL

**Этап 4 — триггеры**
- [ ] `updated_at` меняется после UPDATE (один параметризованный тест, прогнать по списку таблиц с триггером — `users`, `products`, `product_variants`, `carts`, `orders`, `payment_details`, `categories`, `warehouses`, `inventory`)

Итого — примерно 15-18 тестов вместо 30+, и все они реально что-то доказывают про твою БД, а не дублируют то, что уже проверил Flyway при старте.

Хочешь, распишу подробно, как выглядит **один** такой поведенческий тест (например, CHECK на `cart_items.quantity`) с объяснением `assertThrows`, чтобы дальше писал остальные сам по аналогии?