/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.robokassa;

/**
 *
 * @author astrologer
 */
public class Pair<T1, T2>
{
    public T1 left;
    public T2 right;

    public void setLeft(T1 left)
    {
        this.left = left;
    }

    public void setRight(T2 right)
    {
        this.right = right;
    }

    public T1 getLeft()
    {
        return left;
    }

    public T2 getRight()
    {
        return right;
    }

    public Pair()
    {
    }

    public Pair(T1 left, T2 right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public String toString()
    {
        return "(" + left + "," + right + ")";
    }

}