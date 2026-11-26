"""email.policy: how messages are parsed and written (line ending, line length)."""


class Policy:
    def __init__(self, **kw):
        self.linesep = "\n"
        self.max_line_length = 78
        self.cte_type = "8bit"
        self.raise_on_defect = False
        self.mangle_from_ = False
        self.utf8 = False
        self.refold_source = "long"
        self.message_factory = None
        for name, value in kw.items():
            if not hasattr(self, name):
                raise TypeError(f"{name!r} is an invalid keyword argument for {type(self).__name__}")
            setattr(self, name, value)

    def clone(self, **kw):
        new = type(self)()
        for name, value in vars(self).items():
            setattr(new, name, value)
        for name, value in kw.items():
            if not hasattr(new, name):
                raise TypeError(f"{name!r} is an invalid keyword argument for {type(self).__name__}")
            setattr(new, name, value)
        return new

    def __add__(self, other):
        return self.clone(**vars(other))

    def handle_defect(self, obj, defect):
        if self.raise_on_defect:
            raise defect
        obj.defects.append(defect)

    def header_fetch_parse(self, name, value):
        return value


class Compat32(Policy):
    def __repr__(self):
        return "Compat32()"


class EmailPolicy(Policy):
    def __repr__(self):
        return "EmailPolicy()"


compat32 = Compat32()
default = EmailPolicy()
SMTP = default.clone(linesep="\r\n")
SMTPUTF8 = SMTP.clone(utf8=True)
HTTP = default.clone(linesep="\r\n", max_line_length=None)
strict = default.clone(raise_on_defect=True)
