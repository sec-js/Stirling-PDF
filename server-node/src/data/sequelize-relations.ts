import { Sequelize, DataTypes } from "sequelize";

//TODO: Make this configurable
const sequelize = new Sequelize("sqlite::memory:", {
    logging: import.meta.env.VITE_SEQUELIZE_LOGGING === "True" ? console.log : false
});

import { User, AccessRule, Password } from "../auth/user/user-model";
import { APIKey } from "../auth/apikey/apikey-model";

User.init(
    {
        id: {
            type: DataTypes.INTEGER.UNSIGNED,
            autoIncrement: true,
            primaryKey: true,
        },
        username: {
            type: DataTypes.STRING,
            allowNull: false,
            unique: true,
        },
        mail: {
            type: DataTypes.STRING,
            unique: true,
        },
        createdAt: DataTypes.DATE,
        updatedAt: DataTypes.DATE,
    },
    { sequelize },
);

Password.init(
    {
        id: {
            type: DataTypes.INTEGER.UNSIGNED,
            autoIncrement: true,
            primaryKey: true
        },
        password: DataTypes.STRING,
        salt: DataTypes.STRING,
        createdAt: DataTypes.DATE,
        updatedAt: DataTypes.DATE,
    },
    { sequelize },
);
User.hasOne(Password);
Password.belongsTo(User);

AccessRule.init(
    {
        id: {
            type: DataTypes.INTEGER.UNSIGNED,
            autoIncrement: true,
            primaryKey: true
        },
        grants: DataTypes.STRING,
        createdAt: DataTypes.DATE,
        updatedAt: DataTypes.DATE,
    },
    { sequelize },
);
User.hasMany(AccessRule);
AccessRule.belongsTo(User);

APIKey.init(
    {
        id: {
            type: DataTypes.INTEGER.UNSIGNED,
            autoIncrement: true,
            primaryKey: true
        },
        apikey: DataTypes.STRING,
        createdAt: DataTypes.DATE,
        updatedAt: DataTypes.DATE,
    },
    { sequelize },
);
User.hasMany(APIKey);
APIKey.belongsTo(User);

export default sequelize;

(async () => {
    await sequelize.sync({ force: true });
    console.log("Database synced!")
})();