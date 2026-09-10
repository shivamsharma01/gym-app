namespace AccessDemo2s
{
    partial class DeviceInfo
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.pwd_textBox = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.user_textBox = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.port_textBox = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.ip_textBox = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.btn_Login = new System.Windows.Forms.Button();
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.dataGridView1 = new System.Windows.Forms.DataGridView();
            this.UserID = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.FingerprintID = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.FingerprintType = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.comboBox1 = new System.Windows.Forms.ComboBox();
            this.checkedListBox1 = new System.Windows.Forms.CheckedListBox();
            this.btnDistribute = new System.Windows.Forms.Button();
            this.groupBox3 = new System.Windows.Forms.GroupBox();
            this.groupBox1.SuspendLayout();
            this.groupBox2.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dataGridView1)).BeginInit();
            this.groupBox3.SuspendLayout();
            this.SuspendLayout();
            // 
            // pwd_textBox
            // 
            this.pwd_textBox.Location = new System.Drawing.Point(318, 69);
            this.pwd_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.pwd_textBox.Name = "pwd_textBox";
            this.pwd_textBox.Size = new System.Drawing.Size(114, 21);
            this.pwd_textBox.TabIndex = 15;
            this.pwd_textBox.Text = "admin123";
            this.pwd_textBox.UseSystemPasswordChar = true;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(215, 72);
            this.label4.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(95, 12);
            this.label4.TabIndex = 14;
            this.label4.Text = "Password():";
            // 
            // user_textBox
            // 
            this.user_textBox.Location = new System.Drawing.Point(101, 69);
            this.user_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.user_textBox.Name = "user_textBox";
            this.user_textBox.Size = new System.Drawing.Size(105, 21);
            this.user_textBox.TabIndex = 13;
            this.user_textBox.Text = "admin";
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(10, 72);
            this.label3.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(83, 12);
            this.label3.TabIndex = 12;
            this.label3.Text = "Name():";
            // 
            // port_textBox
            // 
            this.port_textBox.Location = new System.Drawing.Point(318, 32);
            this.port_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.port_textBox.Name = "port_textBox";
            this.port_textBox.Size = new System.Drawing.Size(114, 21);
            this.port_textBox.TabIndex = 11;
            this.port_textBox.Text = "80";
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(237, 35);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(71, 12);
            this.label2.TabIndex = 10;
            this.label2.Text = "Port():";
            // 
            // ip_textBox
            // 
            this.ip_textBox.Location = new System.Drawing.Point(101, 32);
            this.ip_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.ip_textBox.Name = "ip_textBox";
            this.ip_textBox.Size = new System.Drawing.Size(105, 21);
            this.ip_textBox.TabIndex = 9;
            this.ip_textBox.Text = "172.13.76.10";
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(22, 35);
            this.label1.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(71, 12);
            this.label1.TabIndex = 8;
            this.label1.Text = "IP(IP):";
            // 
            // btn_Login
            // 
            this.btn_Login.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Login.Location = new System.Drawing.Point(450, 32);
            this.btn_Login.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Login.Name = "btn_Login";
            this.btn_Login.Size = new System.Drawing.Size(113, 58);
            this.btn_Login.TabIndex = 16;
            this.btn_Login.Text = "Login()";
            this.btn_Login.UseVisualStyleBackColor = true;
            this.btn_Login.Click += new System.EventHandler(this.Btn_Login_Click);
            // 
            // groupBox1
            // 
            this.groupBox1.Controls.Add(this.btn_Login);
            this.groupBox1.Controls.Add(this.label1);
            this.groupBox1.Controls.Add(this.pwd_textBox);
            this.groupBox1.Controls.Add(this.ip_textBox);
            this.groupBox1.Controls.Add(this.label4);
            this.groupBox1.Controls.Add(this.label2);
            this.groupBox1.Controls.Add(this.user_textBox);
            this.groupBox1.Controls.Add(this.port_textBox);
            this.groupBox1.Controls.Add(this.label3);
            this.groupBox1.Location = new System.Drawing.Point(21, 12);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Size = new System.Drawing.Size(575, 108);
            this.groupBox1.TabIndex = 17;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "Device Infomation()";
            // 
            // groupBox2
            // 
            this.groupBox2.Controls.Add(this.dataGridView1);
            this.groupBox2.Location = new System.Drawing.Point(21, 138);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Size = new System.Drawing.Size(372, 279);
            this.groupBox2.TabIndex = 18;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "Fingerprint list()";
            // 
            // dataGridView1
            // 
            this.dataGridView1.AllowUserToDeleteRows = false;
            this.dataGridView1.AllowUserToResizeRows = false;
            this.dataGridView1.ColumnHeadersHeightSizeMode = System.Windows.Forms.DataGridViewColumnHeadersHeightSizeMode.AutoSize;
            this.dataGridView1.Columns.AddRange(new System.Windows.Forms.DataGridViewColumn[] {
            this.UserID,
            this.FingerprintID,
            this.FingerprintType});
            this.dataGridView1.Dock = System.Windows.Forms.DockStyle.Fill;
            this.dataGridView1.Location = new System.Drawing.Point(3, 17);
            this.dataGridView1.Name = "dataGridView1";
            this.dataGridView1.ReadOnly = true;
            this.dataGridView1.RowHeadersWidthSizeMode = System.Windows.Forms.DataGridViewRowHeadersWidthSizeMode.AutoSizeToFirstHeader;
            this.dataGridView1.RowTemplate.Height = 23;
            this.dataGridView1.Size = new System.Drawing.Size(366, 259);
            this.dataGridView1.TabIndex = 0;
            // 
            // UserID
            // 
            this.UserID.HeaderText = "User ID()";
            this.UserID.Name = "UserID";
            this.UserID.ReadOnly = true;
            // 
            // FingerprintID
            // 
            this.FingerprintID.HeaderText = "Fingerprint ID()";
            this.FingerprintID.Name = "FingerprintID";
            this.FingerprintID.ReadOnly = true;
            // 
            // FingerprintType
            // 
            this.FingerprintType.HeaderText = "Fingerprint Type()";
            this.FingerprintType.Name = "FingerprintType";
            this.FingerprintType.ReadOnly = true;
            // 
            // comboBox1
            // 
            this.comboBox1.FormattingEnabled = true;
            this.comboBox1.Location = new System.Drawing.Point(5, 28);
            this.comboBox1.Name = "comboBox1";
            this.comboBox1.Size = new System.Drawing.Size(109, 20);
            this.comboBox1.TabIndex = 19;
            // 
            // checkedListBox1
            // 
            this.checkedListBox1.FormattingEnabled = true;
            this.checkedListBox1.Location = new System.Drawing.Point(5, 64);
            this.checkedListBox1.Name = "checkedListBox1";
            this.checkedListBox1.Size = new System.Drawing.Size(228, 212);
            this.checkedListBox1.TabIndex = 20;
            // 
            // btnDistribute
            // 
            this.btnDistribute.Location = new System.Drawing.Point(120, 17);
            this.btnDistribute.Name = "btnDistribute";
            this.btnDistribute.Size = new System.Drawing.Size(113, 41);
            this.btnDistribute.TabIndex = 21;
            this.btnDistribute.Text = "Distribute()";
            this.btnDistribute.UseVisualStyleBackColor = true;
            this.btnDistribute.Click += new System.EventHandler(this.BtnDistribute_Click);
            // 
            // groupBox3
            // 
            this.groupBox3.Controls.Add(this.comboBox1);
            this.groupBox3.Controls.Add(this.checkedListBox1);
            this.groupBox3.Controls.Add(this.btnDistribute);
            this.groupBox3.Location = new System.Drawing.Point(399, 138);
            this.groupBox3.Name = "groupBox3";
            this.groupBox3.Size = new System.Drawing.Size(239, 279);
            this.groupBox3.TabIndex = 22;
            this.groupBox3.TabStop = false;
            // 
            // DeviceInfo
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(6F, 12F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(641, 449);
            this.Controls.Add(this.groupBox3);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Name = "DeviceInfo";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "DeviceInfo";
            this.groupBox1.ResumeLayout(false);
            this.groupBox1.PerformLayout();
            this.groupBox2.ResumeLayout(false);
            ((System.ComponentModel.ISupportInitialize)(this.dataGridView1)).EndInit();
            this.groupBox3.ResumeLayout(false);
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.TextBox pwd_textBox;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.TextBox user_textBox;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.TextBox port_textBox;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox ip_textBox;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Button btn_Login;
        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.DataGridView dataGridView1;
        private System.Windows.Forms.DataGridViewTextBoxColumn UserID;
        private System.Windows.Forms.DataGridViewTextBoxColumn FingerprintID;
        private System.Windows.Forms.DataGridViewTextBoxColumn FingerprintType;
        private System.Windows.Forms.ComboBox comboBox1;
        private System.Windows.Forms.CheckedListBox checkedListBox1;
        private System.Windows.Forms.Button btnDistribute;
        private System.Windows.Forms.GroupBox groupBox3;
    }
}