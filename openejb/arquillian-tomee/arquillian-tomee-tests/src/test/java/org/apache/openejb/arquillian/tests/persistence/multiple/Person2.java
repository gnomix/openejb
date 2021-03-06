package org.apache.openejb.arquillian.tests.persistence.multiple;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@Entity
public class Person2 {
    @Id @GeneratedValue
    private long id;

    private String name;

    public Person2(String name) {
        this.name = name;
    }

    public Person2() {
        // no-op
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
