import random
random.seed(123)
a = [random.random() for _ in range(3)]
random.seed(-9)
b = random.randint(0, 1000)
random.seed(5)
c = random.sample(range(20), 5)
xs = list(range(10))
random.shuffle(xs)
[a, b, c, xs, random.choice([1, 2, 3]), random.randrange(10, 20, 3), random.uniform(5, 6)]
