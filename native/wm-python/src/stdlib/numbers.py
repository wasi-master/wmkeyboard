"""numbers: the numeric abstract base classes. int, float and bool belong."""

from abc import ABCMeta


class Number(metaclass=ABCMeta):
    __hash__ = None


class Complex(Number):
    pass


class Real(Complex):
    pass


class Rational(Real):
    pass


class Integral(Rational):
    pass


Integral.register(int)
Real.register(float)
